(() => {
  if (window.__PocketTransposeInstalled) return;

  const State = {
    ctx: null,
    source: null,
    gain: null,
    media: null,
    connected: false,
    observer: null,
    intervalId: null,
    lastHref: location.href,
    lastError: null,
    pitchSemitone: 0
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

  function ensureAudioContext() {
    const AudioCtx = window.AudioContext || window.webkitAudioContext;
    if (!AudioCtx) {
      log('AudioContext not available');
      return null;
    }
    if (!State.ctx) {
      State.ctx = new AudioCtx();
      log('AudioContext created', State.ctx.sampleRate);
    }
    if (State.ctx.state === 'suspended') {
      State.ctx.resume().catch((e) => log('AudioContext resume failed', e.name, e.message));
    }
    return State.ctx;
  }

  function disconnectGraph() {
    try {
      if (State.source) State.source.disconnect();
      if (State.gain) State.gain.disconnect();
    } catch (e) {
      log('disconnect failed', e.name, e.message);
    }
    State.source = null;
    State.gain = null;
    State.connected = false;
  }

  function hookMedia(media) {
    if (!media) return;
    if (State.media === media && State.connected) return;
    if (State.media && State.media !== media) {
      log('media element changed');
      disconnectGraph();
    }
    if (media.__PocketTransposeHooked) {
      State.media = media;
      State.lastError = { name: 'AlreadyHooked', message: 'Media element was already connected by PocketTranspose' };
      log('media element already hooked');
      return;
    }
    try {
      const ctx = ensureAudioContext();
      if (!ctx) return;
      State.source = ctx.createMediaElementSource(media);
      State.gain = ctx.createGain();
      State.gain.gain.value = 1.0;
      State.source.connect(State.gain);
      State.gain.connect(ctx.destination);
      State.media = media;
      State.connected = true;
      State.lastError = null;
      media.__PocketTransposeHooked = true;
      log('media element source connected');
    } catch (e) {
      State.connected = false;
      State.media = media;
      State.lastError = safeError(e);
      log('createMediaElementSource failed', State.lastError.name, State.lastError.message);
    }
  }

  function scan() {
    if (!document || !document.querySelector) return;
    if (State.lastHref !== location.href) {
      State.lastHref = location.href;
      log('route changed', State.lastHref);
    }
    const media = document.querySelector('video, audio');
    if (!media) return;
    if (State.media !== media) {
      log('media found', media.tagName, media.currentSrc || media.src || '(no src yet)');
    }
    hookMedia(media);
  }

  function patchHistory(name) {
    const original = history[name];
    if (typeof original !== 'function' || original.__PocketTransposePatched) return;
    history[name] = function patchedHistoryMethod(...args) {
      const result = original.apply(this, args);
      setTimeout(scan, 0);
      return result;
    };
    history[name].__PocketTransposePatched = true;
  }

  function installObserver() {
    const root = document.documentElement || document.body;
    if (!root) {
      setTimeout(installObserver, 50);
      return;
    }
    if (!State.observer) {
      State.observer = new MutationObserver(scan);
      State.observer.observe(root, { childList: true, subtree: true });
    }
    if (!State.intervalId) State.intervalId = setInterval(scan, 1000);
    scan();
  }

  window.PocketTranspose = {
    setGain(value) {
      const next = Number(value);
      if (State.gain && Number.isFinite(next)) {
        State.gain.gain.value = next;
        log('gain set', next);
      }
    },
    setPlaybackRate(value) {
      const next = Number(value);
      if (State.media && Number.isFinite(next)) {
        State.media.playbackRate = next;
        if ('preservesPitch' in State.media) State.media.preservesPitch = true;
        log('playbackRate set', next);
      }
    },
    setPitchSemitone(value) {
      State.pitchSemitone = Number(value) || 0;
      log('pitch placeholder set', State.pitchSemitone);
    },
    getStatus() {
      return {
        installed: true,
        connected: State.connected,
        hasMedia: !!State.media,
        audioContextState: State.ctx ? State.ctx.state : null,
        href: location.href,
        lastError: State.lastError,
        pitchSemitone: State.pitchSemitone
      };
    }
  };

  window.__PocketTransposeInstalled = true;
  patchHistory('pushState');
  patchHistory('replaceState');
  window.addEventListener('popstate', scan);
  window.addEventListener('yt-navigate-finish', scan);
  document.addEventListener('visibilitychange', () => {
    if (!document.hidden && State.ctx && State.ctx.state === 'suspended') State.ctx.resume();
  });
  log('script installed');
  installObserver();
})();
