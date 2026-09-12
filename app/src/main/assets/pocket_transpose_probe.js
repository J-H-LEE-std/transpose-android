(() => {
  'use strict';
  if (window.top !== window) return;
  if (window.PocketTranspose) { window.PocketTranspose.retry(); return; }
  const nodes = new WeakMap();
  const active = new Map();
  let failed = new WeakMap();
  const state = { ctx: null, gain: 1, rate: 1, preservesPitch: true, lastError: null };
  const log = (...args) => console.log('[PocketTranspose]', ...args);
  // Error messages can contain signed media URLs. Never forward those to Logcat.
  function failure(error) {
    state.lastError = { name: String(error.name || 'Error'), message: String(error.message || 'Audio operation failed').replace(/(?:https?|blob|data|file):\S+/gi, '[redacted]').slice(0, 240) };
    log(state.lastError.name, state.lastError.message);
  }
  function resume() {
    if (state.ctx && state.ctx.state === 'suspended') state.ctx.resume().catch(failure);
    scan();
  }
  function apply(media) {
    try {
      if (media.playbackRate !== state.rate) media.playbackRate = state.rate;
      media.preservesPitch = state.preservesPitch;
      if ('webkitPreservesPitch' in media) media.webkitPreservesPitch = state.preservesPitch;
    } catch (e) { failure(e); }
  }
  function connect(media) {
    apply(media);
    if (active.has(media)) return;
    const identity = media.currentSrc || media.src || '';
    if (failed.get(media) === identity) return;
    try {
      if (!state.ctx) {
        const Context = window.AudioContext || window.webkitAudioContext;
        if (!Context) throw new Error('AudioContext unavailable');
        state.ctx = new Context();
      }
      let graph = nodes.get(media);
      if (!graph) {
        // A media element may be wrapped only once, even after disconnection.
        const source = state.ctx.createMediaElementSource(media);
        graph = { source, gain: null };
        nodes.set(media, graph);
      }
      if (!graph.gain) graph.gain = state.ctx.createGain();
      graph.gain.gain.value = state.gain;
      graph.source.connect(graph.gain);
      graph.gain.connect(state.ctx.destination);
      active.set(media, graph);
      state.lastError = null;
      log('media found', media.tagName);
      log('media element source connected');
    } catch (e) { failed.set(media, identity); failure(e); }
  }
  function scan() {
    for (const [media, graph] of active) {
      if (!media.isConnected) {
        graph.source.disconnect(); graph.gain.disconnect(); active.delete(media);
      }
    }
    document.querySelectorAll('video, audio').forEach(connect);
  }
  function finite(value, min, max) {
    const n = Number(value);
    if (!Number.isFinite(n)) throw new TypeError('Parameter must be finite');
    return Math.min(max, Math.max(min, n));
  }
  window.PocketTranspose = {
    resume,
    retry() { failed = new WeakMap(); resume(); },
    setGain(value) {
      state.gain = finite(value, 0, 2);
      for (const graph of active.values()) graph.gain.gain.value = state.gain;
      resume();
    },
    setPlaybackRate(value) { state.rate = finite(value, 0.25, 2); resume(); },
    setPreservesPitch(value) { state.preservesPitch = !!value; resume(); },
    setPitchSemitone() { return { supported: false, reason: 'Independent pitch DSP is not implemented' }; },
    getStatus() {
      return { installed: true, connected: active.size > 0, mediaCount: active.size,
        audioContextState: state.ctx ? state.ctx.state : null, gain: state.gain,
        playbackRate: state.rate, preservesPitch: state.preservesPitch,
        independentPitchSupported: false, lastError: state.lastError,
        note: 'Graph connection does not prove audible output; CORS may produce silence.' };
    }
  };
  let queued = false;
  function schedule() {
    if (queued) return;
    queued = true; setTimeout(() => { queued = false; scan(); }, 0);
  }
  function observe() {
    if (!document.documentElement) { setTimeout(observe, 50); return; }
    new MutationObserver(schedule).observe(document.documentElement, { childList: true, subtree: true });
    scan();
  }
  for (const name of ['pushState', 'replaceState']) {
    const original = history[name];
    history[name] = function (...args) { const result = original.apply(this, args); schedule(); return result; };
  }
  for (const event of ['popstate', 'yt-navigate-finish']) window.addEventListener(event, schedule);
  document.addEventListener('play', resume, true);
  document.addEventListener('pointerdown', resume, true);
  document.addEventListener('visibilitychange', () => { if (!document.hidden) resume(); });
  setInterval(scan, 1000);
  observe();
  log('script installed');
})();
