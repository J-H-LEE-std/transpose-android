// PocketTranspose hook: routes the page's <video>/<audio> through Web Audio.
//
//   media -> MediaElementSource -> inputBus -> [pitch shifter] -> gain -> destination
//                                      \-> analyser (silence detection only)
//
// Every media element that was ever hooked stays connected to inputBus, because
// createMediaElementSource() can only be called once per element; disconnecting an
// old element would make it permanently silent if the page switches back to it.
(() => {
  if (window.__PocketTransposeInstalled) {
    // Re-injection (onPageFinished fallback / Reinject button) only triggers a rescan.
    if (window.PocketTranspose && window.PocketTranspose.rescan) window.PocketTranspose.rescan();
    return;
  }
  window.__PocketTransposeInstalled = true;

  // ---------------------------------------------------------------------------
  // Pitch shifter built only from native Web Audio nodes (written for this project).
  //
  // Two delay taps; each tap's delay sweeps linearly through an 80 ms window at
  // (1 - ratio) seconds per second, so its read head advances at `ratio` (pitch
  // changes, duration does not). The taps are half a sweep apart and take turns:
  // a trapezoid window keeps exactly one tap audible most of the time and
  // crossfades (sin^2, sums to 1) over a fixed 20 ms just before the active tap's
  // delay wraps. Overlapping the taps all the time (the first design) summed two
  // copies of the signal 25 ms apart and made the level swing several times a
  // second (audible warble); short handovers cut that wobble 4-7x.
  //
  // The sweep and window curves come from one looping 4-channel AudioBuffer
  // (ramp/window for tap A, ramp/window for tap B) that drives DelayNode.delayTime
  // and GainNode.gain as audio-rate parameters. Everything runs on the audio
  // rendering thread: no AudioWorklet module (blocked by YouTube's CSP
  // 'strict-dynamic') and no ScriptProcessorNode (main-thread jank = crackle).
  const PITCH_WINDOW_SECONDS = 0.08;
  const PITCH_FADE_SECONDS = 0.02;

  // Trapezoid tap window over the delay position a in [0, 1): fade in on [0, f],
  // full on [f, 0.5], fade out on [0.5, 0.5 + f], silent until the wrap at 1.
  // w(a) + w(a + 0.5) = 1 for every a, and w = 0 where the delay jumps.
  function tapWindow(a, f) {
    const rise = (x) => {
      const s = Math.sin((Math.PI / 2) * x);
      return s * s;
    };
    if (a < f) return rise(a / f);
    if (a < 0.5) return 1;
    if (a < 0.5 + f) return 1 - rise((a - 0.5) / f);
    return 0;
  }

  function createNativePitchShifter(ctx) {
    const W = PITCH_WINDOW_SECONDS;
    const frames = Math.round(W * ctx.sampleRate);
    const input = ctx.createGain();
    const output = ctx.createGain();
    const splitter = ctx.createChannelSplitter(4);

    // falling: pitch up (delay shrinking); rising: pitch down (delay growing).
    function buildCurves(falling, fadeFraction) {
      const buffer = ctx.createBuffer(4, frames, ctx.sampleRate);
      const ch = [0, 1, 2, 3].map((c) => buffer.getChannelData(c));
      for (let i = 0; i < frames; i++) {
        let a = i / frames;
        let b = (a + 0.5) % 1;
        if (falling) {
          a = 1 - a;
          b = 1 - b;
        }
        ch[0][i] = a;
        ch[1][i] = tapWindow(a, fadeFraction);
        ch[2][i] = b;
        ch[3][i] = tapWindow(b, fadeFraction);
      }
      return buffer;
    }

    for (let tap = 0; tap < 2; tap++) {
      const delay = ctx.createDelay(W * 2);
      delay.delayTime.value = 0;
      const depth = ctx.createGain();
      depth.gain.value = W;
      const windowGain = ctx.createGain();
      windowGain.gain.value = 0;
      splitter.connect(depth, tap * 2);
      depth.connect(delay.delayTime);
      splitter.connect(windowGain.gain, tap * 2 + 1);
      input.connect(delay);
      delay.connect(windowGain);
      windowGain.connect(output);
    }

    let driver = null;
    let driverKey = null;
    return {
      input,
      output,
      setSemitone(semitone) {
        const ratio = Math.pow(2, semitone / 12);
        const sweep = Math.abs(1 - ratio);
        // A fixed fade time means a larger window fraction for faster sweeps.
        const fadeFraction = Math.min(0.25, Math.max(0.02, (PITCH_FADE_SECONDS * sweep) / W));
        const key = (ratio > 1 ? 'up' : 'down') + ':' + fadeFraction.toFixed(4);
        if (!driver || key !== driverKey) {
          if (driver) {
            try { driver.stop(); } catch (e) { /* already stopped */ }
            driver.disconnect();
          }
          driver = ctx.createBufferSource();
          driver.buffer = buildCurves(ratio > 1, fadeFraction);
          driver.loop = true;
          driver.connect(splitter);
          driver.start();
          driverKey = key;
        }
        // The curve buffer lasts W seconds, so this rate gives a sweep of |1 - ratio| s/s.
        driver.playbackRate.value = sweep;
      }
    };
  }

  // ---------------------------------------------------------------------------
  const State = {
    ctx: null,
    inputBus: null,
    gain: null,
    analyser: null,
    analyserBuffer: null,
    pitch: null,
    pitchEngine: 'none',
    pitchError: null,
    media: null,
    hooked: new WeakSet(),
    listened: new WeakSet(),
    hookedCount: 0,
    lastError: null,
    lastHref: location.href,
    playing: null,
    lastHiddenPause: null,
    routeChanges: 0,
    silenceWarned: false,
    observer: null,
    scanTimer: null,
    settings: { gain: 1, playbackRate: 1, pitchSemitone: 0, preservesPitch: true }
  };

  // Privacy: never log URLs (exception messages can contain media URLs with tokens).
  function redactUrls(value) {
    return typeof value === 'string' ? value.replace(/[a-z][a-z0-9+.-]*:\/\/\S+/gi, '<url>') : value;
  }

  function log(...args) {
    console.log('[PocketTranspose]', ...args.map(redactUrls));
  }

  function safeError(error) {
    return {
      name: error && error.name ? error.name : 'Error',
      message: redactUrls(error && error.message ? error.message : String(error))
    };
  }

  // Never log full media URLs (privacy policy); only the scheme kind.
  function srcKind(media) {
    const src = media.currentSrc || media.src || '';
    if (!src) return '(no src yet)';
    const idx = src.indexOf(':');
    return idx > 0 ? src.slice(0, idx) + ':' : '(relative)';
  }

  function ensureAudioContext() {
    if (State.ctx) return State.ctx;
    const AudioCtx = window.AudioContext || window.webkitAudioContext;
    if (!AudioCtx) {
      log('AudioContext not available');
      return null;
    }
    try {
      State.ctx = new AudioCtx({ latencyHint: 'playback' });
    } catch (e) {
      State.ctx = new AudioCtx();
    }
    const ctx = State.ctx;
    log('AudioContext created', ctx.sampleRate, ctx.state);
    ctx.onstatechange = () => log('AudioContext state', ctx.state);

    State.inputBus = ctx.createGain();
    State.gain = ctx.createGain();
    State.gain.gain.value = State.settings.gain;
    State.analyser = ctx.createAnalyser();
    State.analyser.fftSize = 2048;
    State.analyserBuffer = new Float32Array(State.analyser.fftSize);
    State.inputBus.connect(State.analyser);
    State.gain.connect(ctx.destination);
    rewire();
    return ctx;
  }

  function resumeContext() {
    const ctx = State.ctx;
    if (ctx && ctx.state === 'suspended') {
      ctx.resume().catch((e) => log('AudioContext resume failed', e.name, e.message));
    }
  }

  // inputBus -> (pitch shifter when pitch != 0) -> gain
  function rewire() {
    if (!State.inputBus || !State.gain) return;
    try {
      State.inputBus.disconnect();
      if (State.pitch) State.pitch.output.disconnect();
      State.inputBus.connect(State.analyser);
      if (State.settings.pitchSemitone !== 0 && State.pitch) {
        State.inputBus.connect(State.pitch.input);
        State.pitch.output.connect(State.gain);
      } else {
        State.inputBus.connect(State.gain);
      }
    } catch (e) {
      log('rewire failed', e.name, e.message);
    }
  }

  function ensurePitch() {
    if (State.pitch) return State.pitch;
    const ctx = ensureAudioContext();
    if (!ctx) return null;
    try {
      State.pitch = createNativePitchShifter(ctx);
      State.pitchEngine = 'native-nodes';
      log('pitch engine ready', State.pitchEngine);
    } catch (e) {
      State.pitchEngine = 'unavailable';
      State.pitchError = safeError(e);
      log('pitch engine unavailable', e.name, e.message);
    }
    return State.pitch;
  }

  function applyMediaSettings(media) {
    if (!media) return;
    const s = State.settings;
    try {
      if ('preservesPitch' in media) media.preservesPitch = s.preservesPitch;
      else if ('webkitPreservesPitch' in media) media.webkitPreservesPitch = s.preservesPitch;
      if (media.playbackRate !== s.playbackRate) media.playbackRate = s.playbackRate;
    } catch (e) {
      log('apply media settings failed', e.name, e.message);
    }
  }

  // The app parses these exact lines (console bridge, no JS interface) to keep a
  // media-playback foreground service running while something plays.
  function reportPlayback(playing) {
    if (State.playing === playing) return;
    State.playing = playing;
    log('playback state', playing ? 'playing' : 'paused');
  }

  function anyMediaPlaying() {
    return Array.prototype.slice.call(document.querySelectorAll('video, audio'))
      .some((m) => !m.paused && !m.ended);
  }

  // ---------------------------------------------------------------------------
  // Truthful page visibility while the app keeps the WebView engine "visible".
  //
  // Android WebView pauses hidden <video> itself (by=browser), which mobile Chrome
  // does not. During background playback the app therefore keeps the WebView's
  // window visible to the engine, and this override tells the page the truth
  // instead: document.hidden / visibilityState report "hidden" and the usual
  // visibilitychange + blur events fire. The site then makes its own decision
  // exactly as in a mobile browser (YouTube: keeps playing only for Premium).
  let appHidden = false;
  const visibilityOverrideInstalled = (function installVisibilityOverride() {
    try {
      const proto = window.Document && window.Document.prototype;
      const hidden = proto && Object.getOwnPropertyDescriptor(proto, 'hidden');
      const state = proto && Object.getOwnPropertyDescriptor(proto, 'visibilityState');
      if (!hidden || !hidden.get || !state || !state.get) return false;
      const define = (name, get) => Object.defineProperty(document, name, { configurable: true, get });
      define('hidden', function () { return appHidden || hidden.get.call(this); });
      define('visibilityState', function () { return appHidden ? 'hidden' : state.get.call(this); });
      const webkitHidden = Object.getOwnPropertyDescriptor(proto, 'webkitHidden');
      if (webkitHidden && webkitHidden.get) {
        define('webkitHidden', function () { return appHidden || webkitHidden.get.call(this); });
      }
      const webkitState = Object.getOwnPropertyDescriptor(proto, 'webkitVisibilityState');
      if (webkitState && webkitState.get) {
        define('webkitVisibilityState', function () { return appHidden ? 'hidden' : webkitState.get.call(this); });
      }
      const hasFocus = document.hasFocus;
      if (typeof hasFocus === 'function') {
        document.hasFocus = function () { return !appHidden && hasFocus.call(this); };
      }
      return true;
    } catch (e) {
      log('visibility override failed', e.name, e.message);
      return false;
    }
  })();

  // Applies the app's background state here and forwards it to child frames
  // (embedded players), which run their own copy of this script.
  function applyAppHidden(hidden) {
    if (!visibilityOverrideInstalled) return false;
    if (appHidden !== hidden) {
      appHidden = hidden;
      document.dispatchEvent(new Event('visibilitychange'));
      window.dispatchEvent(new Event(hidden ? 'blur' : 'focus'));
    }
    const frames = window.frames;
    for (let i = 0; i < (frames ? frames.length : 0); i++) {
      try {
        frames[i].postMessage({ __pocketTransposeAppHidden: hidden }, '*');
      } catch (e) { /* frame gone */ }
    }
    return true;
  }

  window.addEventListener('message', (event) => {
    const data = event.data;
    if (event.source !== window.parent || window.parent === window) return;
    if (data && typeof data.__pocketTransposeAppHidden === 'boolean') applyAppHidden(data.__pocketTransposeAppHidden);
  });

  // Diagnostics: was a pause requested by page script (e.g. YouTube's player), by
  // this app (PocketTranspose.pause), or done by the browser itself (no pause() call)?
  const PAUSE_CALL_WINDOW_MS = 1000;
  let appPauseInProgress = false;
  (function wrapPause() {
    const proto = window.HTMLMediaElement && window.HTMLMediaElement.prototype;
    if (!proto || typeof proto.pause !== 'function' || proto.pause.__PocketTransposeWrapped) return;
    const original = proto.pause;
    const wrapped = function (...args) {
      try {
        this.__PocketTransposePauseCall = { at: Date.now(), byApp: appPauseInProgress };
      } catch (e) { /* non-extensible element: diagnostics only */ }
      return original.apply(this, args);
    };
    wrapped.__PocketTransposeWrapped = true;
    proto.pause = wrapped;
  })();

  function pauseSource(media) {
    const call = media.__PocketTransposePauseCall;
    if (!call || Date.now() - call.at > PAUSE_CALL_WINDOW_MS) return 'browser';
    return call.byApp ? 'app' : 'page-script';
  }

  function listenMedia(media) {
    if (State.listened.has(media)) return;
    State.listened.add(media);
    const onPlay = () => {
      resumeContext();
      reportPlayback(true);
    };
    const onStop = () => {
      if (document.hidden) {
        State.lastHiddenPause = pauseSource(media);
        log('media paused while page hidden', 'by=' + State.lastHiddenPause);
      }
      reportPlayback(anyMediaPlaying());
    };
    media.addEventListener('play', onPlay);
    media.addEventListener('playing', onPlay);
    media.addEventListener('pause', onStop);
    media.addEventListener('ended', onStop);
    media.addEventListener('emptied', onStop);
    media.addEventListener('loadedmetadata', () => {
      if (media === State.media) applyMediaSettings(media);
    });
    if (!media.paused && !media.ended) reportPlayback(true);
  }

  function hookMedia(media) {
    listenMedia(media);
    if (State.hooked.has(media)) return;
    const ctx = ensureAudioContext();
    if (!ctx) return;
    try {
      const source = ctx.createMediaElementSource(media);
      source.connect(State.inputBus);
      State.hooked.add(media);
      State.hookedCount++;
      State.lastError = null;
      State.silenceWarned = false;
      log('media element source connected', media.tagName, 'crossOrigin=' + (media.crossOrigin || 'none'));
    } catch (e) {
      State.lastError = safeError(e);
      log('createMediaElementSource failed', State.lastError.name, State.lastError.message);
    }
    resumeContext();
  }

  function pickMedia() {
    const list = Array.prototype.slice.call(document.querySelectorAll('video, audio'));
    if (!list.length) return null;
    return (
      list.find((m) => !m.paused && !m.ended) ||
      list.find((m) => m.currentSrc || m.src) ||
      list[0]
    );
  }

  function scan() {
    if (!document.querySelector) return;
    if (State.lastHref !== location.href) {
      State.lastHref = location.href;
      State.routeChanges++;
      log('route changed; rescanning', '#' + State.routeChanges);
    }
    const media = pickMedia();
    if (!media) return;
    if (State.media !== media) {
      if (State.media) log('media element changed');
      log('media found', media.tagName, srcKind(media));
      State.media = media;
      applyMediaSettings(media);
    }
    hookMedia(media);
  }

  // MutationObserver fires constantly on YouTube; coalesce scans.
  function scheduleScan(delay) {
    if (State.scanTimer) return;
    State.scanTimer = setTimeout(() => {
      State.scanTimer = null;
      scan();
    }, delay || 150);
  }

  function onRouteSignal() {
    setTimeout(scan, 0);
    setTimeout(scan, 500);
    setTimeout(scan, 1500);
  }

  function patchHistory(name) {
    const original = history[name];
    if (typeof original !== 'function' || original.__PocketTransposePatched) return;
    const patched = function (...args) {
      const result = original.apply(this, args);
      onRouteSignal();
      return result;
    };
    patched.__PocketTransposePatched = true;
    history[name] = patched;
  }

  function inputLevelDb() {
    if (!State.analyser) return null;
    State.analyser.getFloatTimeDomainData(State.analyserBuffer);
    let sum = 0;
    for (let i = 0; i < State.analyserBuffer.length; i++) sum += State.analyserBuffer[i] * State.analyserBuffer[i];
    const rms = Math.sqrt(sum / State.analyserBuffer.length);
    return rms > 0 ? Math.round(20 * Math.log10(rms) * 10) / 10 : -Infinity;
  }

  // Detects the "connected but silent" failure (CORS / MSE tainting).
  function checkSilence() {
    const media = State.media;
    if (!media || !State.hooked.has(media) || media.paused || media.muted || media.volume === 0) return;
    if (!State.ctx || State.ctx.state !== 'running' || media.currentTime < 1) return;
    const db = inputLevelDb();
    if (db === -Infinity || db < -90) {
      if (!State.silenceWarned) {
        State.silenceWarned = true;
        log('WARNING media is playing but Web Audio input is silent (possible CORS/MSE silence)');
      }
    } else if (State.silenceWarned) {
      State.silenceWarned = false;
      log('Web Audio input signal detected', db, 'dBFS');
    }
  }

  function installObserver() {
    const root = document.documentElement;
    if (!root) {
      setTimeout(installObserver, 50);
      return;
    }
    if (!State.observer) {
      State.observer = new MutationObserver(() => scheduleScan());
      State.observer.observe(root, { childList: true, subtree: true });
    }
    setInterval(scan, 1000);
    setInterval(checkSilence, 3000);
    scan();
  }

  function clamp(value, min, max, fallback) {
    const n = Number(value);
    if (!Number.isFinite(n)) return fallback;
    return Math.min(max, Math.max(min, n));
  }

  window.PocketTranspose = {
    rescan() {
      log('rescan requested');
      scan();
      resumeContext();
    },
    resume() {
      resumeContext();
    },
    // Called by the app when it goes to / returns from the background. Returns
    // false when the page cannot be told (the app then lets the engine hide it).
    setAppHidden(hidden) {
      const ok = applyAppHidden(!!hidden);
      log('app hidden', !!hidden, ok ? '' : '(visibility override unavailable)');
      return ok;
    },
    pause() {
      appPauseInProgress = true;
      try {
        Array.prototype.slice.call(document.querySelectorAll('video, audio')).forEach((m) => {
          if (!m.paused) m.pause();
        });
      } finally {
        appPauseInProgress = false;
      }
    },
    setGain(value) {
      State.settings.gain = clamp(value, 0, 4, 1);
      if (State.gain) State.gain.gain.value = State.settings.gain;
      log('gain set', State.settings.gain);
    },
    setPlaybackRate(value) {
      State.settings.playbackRate = clamp(value, 0.25, 4, 1);
      applyMediaSettings(State.media);
      log('playbackRate set', State.settings.playbackRate);
    },
    setPreservesPitch(value) {
      State.settings.preservesPitch = !!value;
      applyMediaSettings(State.media);
      log('preservesPitch set', State.settings.preservesPitch);
    },
    setPitchSemitone(value) {
      State.settings.pitchSemitone = Math.round(clamp(value, -12, 12, 0));
      log('pitch semitone set', State.settings.pitchSemitone);
      if (State.settings.pitchSemitone === 0) {
        rewire();
        return;
      }
      const pitch = ensurePitch();
      if (pitch) pitch.setSemitone(State.settings.pitchSemitone);
      rewire();
    },
    applySettings(settings) {
      if (!settings) return;
      if ('gain' in settings) this.setGain(settings.gain);
      if ('preservesPitch' in settings) this.setPreservesPitch(settings.preservesPitch);
      if ('playbackRate' in settings) this.setPlaybackRate(settings.playbackRate);
      if ('pitchSemitone' in settings) this.setPitchSemitone(settings.pitchSemitone);
    },
    getStatus() {
      const media = State.media;
      const ctx = State.ctx;
      return {
        installed: true,
        mode: 'hook',
        connected: !!media && State.hooked.has(media),
        hasMedia: !!media,
        mediaTag: media ? media.tagName : null,
        mediaSrcKind: media ? srcKind(media) : null,
        mediaPaused: media ? media.paused : null,
        mediaPlaybackRate: media ? media.playbackRate : null,
        mediaPreservesPitch: media && 'preservesPitch' in media ? media.preservesPitch : null,
        hookedCount: State.hookedCount,
        audioContextState: ctx ? ctx.state : null,
        sampleRate: ctx ? ctx.sampleRate : null,
        baseLatency: ctx && 'baseLatency' in ctx ? ctx.baseLatency : null,
        inputLevelDb: State.analyser ? String(inputLevelDb()) : null,
        silenceSuspected: State.silenceWarned,
        pitchEngine: State.pitchEngine,
        pitchError: State.pitchError,
        lastError: State.lastError,
        routeChanges: State.routeChanges,
        playing: !!State.playing,
        lastHiddenPause: State.lastHiddenPause,
        appHidden,
        visibilityOverrideInstalled,
        settings: Object.assign({}, State.settings)
      };
    }
  };

  patchHistory('pushState');
  patchHistory('replaceState');
  window.addEventListener('popstate', onRouteSignal);
  window.addEventListener('yt-navigate-finish', onRouteSignal);
  document.addEventListener('yt-navigate-finish', onRouteSignal);
  // Any user gesture is a chance to leave the "suspended" autoplay state.
  window.addEventListener('pointerdown', resumeContext, true);
  window.addEventListener('touchend', resumeContext, true);
  // Diagnostics for background playback: tells "the page paused itself when hidden"
  // apart from "the app/OS stopped playback".
  document.addEventListener('visibilitychange', () => {
    log(document.hidden ? 'page hidden' : 'page visible', 'mediaPlaying=' + anyMediaPlaying());
    if (!document.hidden) resumeContext();
  });

  log('script installed');
  installObserver();
})();
