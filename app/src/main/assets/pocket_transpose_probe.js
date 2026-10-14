// PocketTranspose Phase 1 probe: detects <video>/<audio> only. It never touches the
// Web Audio graph, so it can be used to compare behaviour against the full hook.
(() => {
  if (window.__PocketTransposeProbeInstalled) {
    if (window.PocketTranspose && window.PocketTranspose.rescan) window.PocketTranspose.rescan();
    return;
  }
  window.__PocketTransposeProbeInstalled = true;

  let lastMedia = null;
  let routeChanges = 0;

  // Privacy: never log URLs.
  function log(...args) {
    console.log('[PocketTranspose]', ...args.map((v) =>
      typeof v === 'string' ? v.replace(/[a-z][a-z0-9+.-]*:\/\/\S+/gi, '<url>') : v));
  }

  // Never log full media URLs (privacy policy); only the scheme kind.
  function srcKind(media) {
    const src = media.currentSrc || media.src || '';
    if (!src) return '(no src yet)';
    const idx = src.indexOf(':');
    return idx > 0 ? src.slice(0, idx) + ':' : '(relative)';
  }

  function findMedia() {
    if (!document.querySelector) return null;
    const media = document.querySelector('video, audio');
    if (media && media !== lastMedia) {
      lastMedia = media;
      log('media found', media.tagName, srcKind(media));
    }
    return media;
  }

  function onRoute() {
    routeChanges++;
    log('route changed; rescanning');
    setTimeout(findMedia, 0);
  }

  ['pushState', 'replaceState'].forEach((name) => {
    const original = history[name];
    if (typeof original !== 'function') return;
    history[name] = function (...args) {
      const result = original.apply(this, args);
      onRoute();
      return result;
    };
  });
  window.addEventListener('popstate', onRoute);
  window.addEventListener('yt-navigate-finish', onRoute);

  window.PocketTranspose = {
    rescan() { findMedia(); },
    setGain() { log('probe mode: setGain ignored'); },
    setPlaybackRate() { log('probe mode: setPlaybackRate ignored'); },
    setPitchSemitone() { log('probe mode: setPitchSemitone ignored'); },
    setPreservesPitch() { log('probe mode: setPreservesPitch ignored'); },
    applySettings() {},
    resume() {},
    getStatus() {
      return {
        installed: true,
        mode: 'probe',
        hasMedia: !!lastMedia,
        mediaTag: lastMedia ? lastMedia.tagName : null,
        mediaSrcKind: lastMedia ? srcKind(lastMedia) : null,
        routeChanges
      };
    }
  };

  function installObserver() {
    const root = document.documentElement;
    if (!root) {
      setTimeout(installObserver, 50);
      return;
    }
    new MutationObserver(findMedia).observe(root, { childList: true, subtree: true });
    setInterval(findMedia, 1000);
    findMedia();
  }

  log('script installed (probe mode)');
  installObserver();
})();
