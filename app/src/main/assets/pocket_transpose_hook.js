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
  // DSP: two-tap delay-line pitch shifter, written from scratch for this project.
  // Two read taps sweep through a delay window at (1 - ratio) samples per sample,
  // so the read head advances at `ratio`. Each tap jumps when its phase wraps; the
  // jump happens where its sin^2 window is zero, and the windows of the two taps
  // (half a period apart) always sum to 1. Duration is unchanged, pitch changes.
  // This function is also stringified into the AudioWorklet module, so it must stay
  // self-contained.
  function definePitchShifter() {
    return class PocketPitchShifter {
      constructor(sampleRate, channels) {
        this.windowSize = Math.max(256, Math.round(sampleRate * 0.05));
        let size = 1;
        while (size < this.windowSize * 2 + 8) size <<= 1;
        this.mask = size - 1;
        this.buffers = [];
        for (let c = 0; c < channels; c++) this.buffers.push(new Float32Array(size));
        this.writeIndex = 0;
        this.phase = 0;
        this.ratio = 1;
      }

      setSemitone(semitone) {
        const s = Number(semitone);
        this.ratio = Number.isFinite(s) ? Math.pow(2, s / 12) : 1;
      }

      read(buffer, position) {
        const i0 = Math.floor(position);
        const frac = position - i0;
        const a = buffer[i0 & this.mask];
        const b = buffer[(i0 + 1) & this.mask];
        return a + (b - a) * frac;
      }

      // inputs/outputs: arrays of Float32Array (one per channel).
      process(inputs, outputs, frames) {
        const W = this.windowSize;
        const step = (1 - this.ratio) / W;
        const channels = this.buffers.length;
        for (let i = 0; i < frames; i++) {
          const w = this.writeIndex;
          for (let c = 0; c < channels; c++) {
            const input = inputs[c] || inputs[0];
            this.buffers[c][w] = input ? input[i] : 0;
          }

          let p1 = this.phase + step;
          p1 -= Math.floor(p1);
          this.phase = p1;
          let p2 = p1 + 0.5;
          if (p2 >= 1) p2 -= 1;

          const s1 = Math.sin(Math.PI * p1);
          const s2 = Math.sin(Math.PI * p2);
          const g1 = s1 * s1;
          const g2 = s2 * s2;
          const pos1 = w - p1 * W;
          const pos2 = w - p2 * W;

          for (let c = 0; c < outputs.length; c++) {
            const buffer = this.buffers[Math.min(c, channels - 1)];
            outputs[c][i] = g1 * this.read(buffer, pos1) + g2 * this.read(buffer, pos2);
          }
          this.writeIndex = (w + 1) & this.mask;
        }
      }
    };
  }

  const PocketPitchShifter = definePitchShifter();
  const WORKLET_NAME = 'pocket-transpose-pitch';

  function workletSource() {
    return [
      'const PocketPitchShifter = (' + definePitchShifter.toString() + ')();',
      'class PocketPitchProcessor extends AudioWorkletProcessor {',
      '  constructor() {',
      '    super();',
      '    this.shifter = new PocketPitchShifter(sampleRate, 2);',
      '    this.port.onmessage = (e) => {',
      "      if (e.data && typeof e.data.semitone === 'number') this.shifter.setSemitone(e.data.semitone);",
      '    };',
      '  }',
      '  process(inputs, outputs) {',
      '    const output = outputs[0];',
      '    if (!output || !output.length) return true;',
      '    this.shifter.process(inputs[0] || [], output, output[0].length);',
      '    return true;',
      '  }',
      '}',
      "registerProcessor('" + WORKLET_NAME + "', PocketPitchProcessor);"
    ].join('\n');
  }

  // ---------------------------------------------------------------------------
  const State = {
    ctx: null,
    inputBus: null,
    gain: null,
    analyser: null,
    analyserBuffer: null,
    pitchNode: null,
    pitchEngine: 'none',
    pitchLoading: null,
    pitchError: null,
    media: null,
    hooked: new WeakSet(),
    listened: new WeakSet(),
    hookedCount: 0,
    lastError: null,
    lastHref: location.href,
    routeChanges: 0,
    silenceWarned: false,
    observer: null,
    scanTimer: null,
    settings: { gain: 1, playbackRate: 1, pitchSemitone: 0, preservesPitch: true }
  };

  function log(...args) {
    console.log('[PocketTranspose]', ...args);
  }

  function safeError(error) {
    return {
      name: error && error.name ? error.name : 'Error',
      message: error && error.message ? error.message : String(error)
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

  // inputBus -> (pitchNode when pitch != 0 and ready) -> gain
  function rewire() {
    if (!State.inputBus || !State.gain) return;
    try {
      State.inputBus.disconnect();
      if (State.pitchNode) State.pitchNode.disconnect();
      State.inputBus.connect(State.analyser);
      if (State.settings.pitchSemitone !== 0 && State.pitchNode) {
        State.inputBus.connect(State.pitchNode);
        State.pitchNode.connect(State.gain);
      } else {
        State.inputBus.connect(State.gain);
      }
    } catch (e) {
      log('rewire failed', e.name, e.message);
    }
  }

  function sendPitchToNode() {
    const s = State.settings.pitchSemitone;
    if (!State.pitchNode) return;
    if (State.pitchEngine === 'audioworklet') State.pitchNode.port.postMessage({ semitone: s });
    else if (State.pitchNode.__shifter) State.pitchNode.__shifter.setSemitone(s);
  }

  function createScriptProcessorPitch(ctx) {
    if (typeof ctx.createScriptProcessor !== 'function') throw new Error('ScriptProcessorNode not available');
    const node = ctx.createScriptProcessor(2048, 2, 2);
    const shifter = new PocketPitchShifter(ctx.sampleRate, 2);
    node.__shifter = shifter;
    node.onaudioprocess = (event) => {
      const inBuf = event.inputBuffer;
      const outBuf = event.outputBuffer;
      const inputs = [];
      for (let c = 0; c < inBuf.numberOfChannels; c++) inputs.push(inBuf.getChannelData(c));
      const outputs = [];
      for (let c = 0; c < outBuf.numberOfChannels; c++) outputs.push(outBuf.getChannelData(c));
      shifter.process(inputs, outputs, outBuf.length);
    };
    return node;
  }

  async function createWorkletPitch(ctx) {
    if (!ctx.audioWorklet || typeof window.AudioWorkletNode !== 'function') {
      throw new Error('AudioWorklet not available (requires a secure context)');
    }
    const url = URL.createObjectURL(new Blob([workletSource()], { type: 'application/javascript' }));
    try {
      await ctx.audioWorklet.addModule(url);
    } finally {
      URL.revokeObjectURL(url);
    }
    return new AudioWorkletNode(ctx, WORKLET_NAME, {
      numberOfInputs: 1,
      numberOfOutputs: 1,
      outputChannelCount: [2]
    });
  }

  function ensurePitchNode() {
    if (State.pitchNode) return Promise.resolve(State.pitchNode);
    if (State.pitchLoading) return State.pitchLoading;
    const ctx = ensureAudioContext();
    if (!ctx) return Promise.resolve(null);

    State.pitchLoading = createWorkletPitch(ctx)
      .then((node) => {
        State.pitchEngine = 'audioworklet';
        return node;
      })
      .catch((e) => {
        State.pitchError = safeError(e);
        log('AudioWorklet pitch failed; trying ScriptProcessorNode', e.name, e.message);
        const node = createScriptProcessorPitch(ctx);
        State.pitchEngine = 'scriptprocessor';
        return node;
      })
      .then((node) => {
        State.pitchNode = node;
        log('pitch engine ready', State.pitchEngine);
        return node;
      })
      .catch((e) => {
        State.pitchEngine = 'unavailable';
        State.pitchError = safeError(e);
        log('pitch engine unavailable', e.name, e.message);
        return null;
      })
      .finally(() => {
        State.pitchLoading = null;
      });
    return State.pitchLoading;
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

  function listenMedia(media) {
    if (State.listened.has(media)) return;
    State.listened.add(media);
    media.addEventListener('play', resumeContext);
    media.addEventListener('playing', resumeContext);
    media.addEventListener('loadedmetadata', () => {
      if (media === State.media) applyMediaSettings(media);
    });
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
      ensurePitchNode().then(() => {
        sendPitchToNode();
        rewire();
      });
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
  document.addEventListener('visibilitychange', () => {
    if (!document.hidden) resumeContext();
  });

  log('script installed');
  installObserver();
})();
