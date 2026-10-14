// Regression tests for the injected scripts. Node built-ins only (node:test + vm);
// the page is replaced by small fakes of the DOM / Web Audio APIs the scripts use.
// Run: node --test tests/
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const ASSETS = path.join(__dirname, '..', 'app', 'src', 'main', 'assets');
const HOOK = fs.readFileSync(path.join(ASSETS, 'pocket_transpose_hook.js'), 'utf8');
const PROBE = fs.readFileSync(path.join(ASSETS, 'pocket_transpose_probe.js'), 'utf8');

class FakeParam {
  constructor(value) { this.value = value; }
}

class FakeNode {
  constructor(kind) { this.kind = kind; this.outputs = new Set(); }
  connect(target) { this.outputs.add(target); return target; }
  disconnect() { this.outputs.clear(); }
}

class FakeAudioContext {
  constructor() {
    this.sampleRate = 48000;
    this.state = 'running';
    this.baseLatency = 0.01;
    this.destination = new FakeNode('destination');
    this.mediaSources = [];
    this.bufferSources = [];
    this.inputLevel = 0.1;
    FakeAudioContext.last = this;
  }
  resume() { this.state = 'running'; return Promise.resolve(); }
  createGain() { const n = new FakeNode('gain'); n.gain = new FakeParam(1); return n; }
  createAnalyser() {
    const n = new FakeNode('analyser');
    n.fftSize = 2048;
    n.getFloatTimeDomainData = (array) => array.fill(this.inputLevel);
    return n;
  }
  createDelay() { const n = new FakeNode('delay'); n.delayTime = new FakeParam(0); return n; }
  createChannelSplitter() { return new FakeNode('splitter'); }
  createBuffer(channels, length, sampleRate) {
    const data = Array.from({ length: channels }, () => new Float32Array(length));
    return { numberOfChannels: channels, length, sampleRate, getChannelData: (c) => data[c] };
  }
  createBufferSource() {
    const n = new FakeNode('bufferSource');
    n.playbackRate = new FakeParam(1);
    n.start = () => { n.started = true; };
    n.stop = () => { n.stopped = true; };
    this.bufferSources.push(n);
    return n;
  }
  createMediaElementSource(media) {
    if (media.fail) {
      const error = new Error('Blocked https://media.example.test/v.mp4?token=secret');
      error.name = 'SecurityError';
      throw error;
    }
    if (media.wrapped) throw new Error('already connected to a MediaElementSourceNode');
    media.wrapped = true;
    const n = new FakeNode('mediaSource');
    this.mediaSources.push(n);
    return n;
  }
}

function fakeMedia(overrides = {}) {
  return {
    tagName: 'VIDEO',
    paused: false,
    ended: false,
    muted: false,
    volume: 1,
    currentTime: 5,
    currentSrc: 'https://media.example.test/watch?v=abc&token=secret',
    src: '',
    crossOrigin: null,
    playbackRate: 1,
    preservesPitch: true,
    listeners: {},
    addEventListener(type, fn) { (this.listeners[type] ||= []).push(fn); },
    fire(type) { (this.listeners[type] || []).forEach((fn) => fn()); },
    pause() { this.paused = true; this.fire('pause'); },
    ...overrides
  };
}

function load(code, windowExtras = {}, documentOverride = null) {
  let media = [];
  const timeouts = [];
  const intervals = [];
  const logs = [];
  const window = { addEventListener() {}, AudioContext: FakeAudioContext, ...windowExtras };
  const context = vm.createContext({
    window,
    document: documentOverride || {
      documentElement: {},
      hidden: false,
      addEventListener() {},
      querySelector: () => media[0] || null,
      querySelectorAll: () => media
    },
    location: { href: 'https://m.youtube.com/', origin: 'https://m.youtube.com' },
    history: { pushState() {}, replaceState() {} },
    console: { log: (...args) => logs.push(args.join(' ')) },
    MutationObserver: class { observe() {} },
    Event: class { constructor(type) { this.type = type; } },
    setTimeout: (fn) => { timeouts.push(fn); return timeouts.length; },
    setInterval: (fn) => { intervals.push(fn); return intervals.length; }
  });
  const run = () => vm.runInContext(code, context);
  run();
  return {
    api: () => window.PocketTranspose,
    status: () => window.PocketTranspose.getStatus(),
    setMedia: (list) => { media = list; },
    scan: () => intervals[0](),
    checkSilence: () => intervals[1](),
    flushTimeouts: () => { while (timeouts.length) timeouts.shift()(); },
    rerun: run,
    location: context.location,
    document: context.document,
    setMediaGetter: (fn) => { media = fn; },
    mediaRef: () => media,
    logs
  };
}

test('hook installs once; reinjection only rescans', () => {
  const h = load(HOOK);
  assert.equal(h.logs.filter((l) => l.includes('script installed')).length, 1);
  h.rerun();
  assert.equal(h.logs.filter((l) => l.includes('script installed')).length, 1);
  assert.ok(h.logs.some((l) => l.includes('rescan requested')));
  assert.equal(h.status().installed, true);
  assert.equal(h.status().mode, 'hook');
});

test('each media element gets exactly one source, and old elements stay hooked', () => {
  const h = load(HOOK);
  const a = fakeMedia();
  const b = fakeMedia();
  h.setMedia([a]);
  h.scan();
  assert.ok(h.logs.some((l) => l.includes('media element source connected')));
  h.setMedia([b]);
  h.scan();
  h.setMedia([a]);
  h.scan();
  const ctx = FakeAudioContext.last;
  assert.equal(ctx.mediaSources.length, 2);
  assert.equal(h.status().connected, true);
  assert.equal(h.status().hookedCount, 2);
});

test('settings are clamped, applied, and carried over to new media', () => {
  const h = load(HOOK);
  const a = fakeMedia();
  h.setMedia([a]);
  h.scan();
  h.api().setGain(10);
  assert.equal(h.status().settings.gain, 4);
  h.api().setGain('not a number');
  assert.equal(h.status().settings.gain, 1);
  h.api().setPlaybackRate(10);
  assert.equal(a.playbackRate, 4);
  h.api().setPlaybackRate(0.1);
  assert.equal(a.playbackRate, 0.25);
  h.api().setPreservesPitch(false);
  assert.equal(a.preservesPitch, false);

  const b = fakeMedia();
  h.setMedia([b]);
  h.scan();
  assert.equal(b.playbackRate, 0.25);
  assert.equal(b.preservesPitch, false);
});

test('pitch uses native nodes, is clamped to +/-12, and drives the sweep rate', () => {
  const h = load(HOOK);
  h.setMedia([fakeMedia()]);
  h.scan();
  const ctx = FakeAudioContext.last;

  h.api().setPitchSemitone(7);
  assert.equal(h.status().pitchEngine, 'native-nodes');
  const up = ctx.bufferSources.at(-1);
  assert.ok(up.started);
  assert.ok(Math.abs(up.playbackRate.value - (Math.pow(2, 7 / 12) - 1)) < 1e-9);

  // Changing direction swaps the curve buffer and stops the old driver.
  h.api().setPitchSemitone(-5);
  const down = ctx.bufferSources.at(-1);
  assert.notEqual(down, up);
  assert.ok(up.stopped);
  assert.ok(Math.abs(down.playbackRate.value - (1 - Math.pow(2, -5 / 12))) < 1e-9);

  h.api().setPitchSemitone(30);
  assert.equal(h.status().settings.pitchSemitone, 12);
  h.api().setPitchSemitone(-30);
  assert.equal(h.status().settings.pitchSemitone, -12);
});

test('pitch curves: ramps sweep 0..1 in the right direction and windows sum to 1', () => {
  const h = load(HOOK);
  h.setMedia([fakeMedia()]);
  h.scan();
  const ctx = FakeAudioContext.last;
  for (const [semitone, falling] of [[3, true], [-3, false]]) {
    h.api().setPitchSemitone(semitone);
    const buffer = ctx.bufferSources.at(-1).buffer;
    const [rampA, windowA, rampB, windowB] = [0, 1, 2, 3].map((c) => buffer.getChannelData(c));
    assert.equal(buffer.length, Math.round(0.05 * ctx.sampleRate));
    assert.equal(rampA[10] > rampA[100], falling);
    for (let i = 0; i < buffer.length; i++) {
      assert.ok(rampA[i] >= 0 && rampA[i] <= 1 && rampB[i] >= 0 && rampB[i] <= 1);
      assert.ok(Math.abs(windowA[i] + windowB[i] - 1) < 1e-6);
    }
  }
});

test('failures are reported without leaking URLs; status has no page URL', () => {
  const h = load(HOOK);
  h.setMedia([fakeMedia({ fail: true })]);
  h.scan();
  const status = h.status();
  assert.equal(status.connected, false);
  assert.equal(status.lastError.name, 'SecurityError');
  assert.equal(status.mediaSrcKind, 'https:');
  assert.equal('href' in status, false);
  assert.equal(JSON.stringify(status).includes('secret'), false);
  assert.equal(h.logs.some((l) => l.includes('secret')), false);
});

test('SPA navigation triggers a rescan', () => {
  const h = load(HOOK);
  h.setMedia([fakeMedia()]);
  h.scan();
  h.location.href = 'https://m.youtube.com/watch?v=next';
  h.scan();
  assert.equal(h.status().routeChanges, 1);
});

test('silent Web Audio input while playing is flagged', () => {
  const h = load(HOOK);
  h.setMedia([fakeMedia()]);
  h.scan();
  FakeAudioContext.last.inputLevel = 0;
  h.checkSilence();
  assert.equal(h.status().silenceSuspected, true);
  assert.ok(h.logs.some((l) => l.includes('Web Audio input is silent')));
  FakeAudioContext.last.inputLevel = 0.1;
  h.checkSilence();
  assert.equal(h.status().silenceSuspected, false);
});

test('probe finds media without touching Web Audio', () => {
  const h = load(PROBE);
  h.setMedia([fakeMedia({ tagName: 'AUDIO' })]);
  h.scan();
  assert.ok(h.logs.some((l) => l.includes('media found AUDIO https:')));
  assert.equal(h.status().mode, 'probe');
  assert.equal(h.status().hasMedia, true);
  assert.equal(h.logs.some((l) => l.includes('secret')), false);
});

test('playback state is reported once per change for the app foreground service', () => {
  const h = load(HOOK);
  const media = fakeMedia({ paused: true });
  h.setMedia([media]);
  h.scan();
  const states = () => h.logs.filter((l) => l.includes('playback state'));
  assert.equal(states().length, 0);

  media.paused = false;
  media.fire('play');
  media.fire('playing');
  assert.deepEqual(states(), ['[PocketTranspose] playback state playing']);
  assert.equal(h.status().playing, true);

  h.api().pause();
  assert.equal(media.paused, true);
  assert.deepEqual(states().at(-1), '[PocketTranspose] playback state paused');
  assert.equal(h.status().playing, false);
});

test('pausing one element does not report paused while another still plays', () => {
  const h = load(HOOK);
  const a = fakeMedia();
  const b = fakeMedia();
  h.setMedia([a, b]);
  h.scan();
  a.paused = true;
  a.fire('pause');
  assert.equal(h.status().playing, true);
  assert.equal(h.logs.some((l) => l.includes('playback state paused')), false);
});

test('pause source is attributed when the page is hidden', () => {
  // Fake prototype so the hook can wrap pause() like HTMLMediaElement.prototype.pause.
  class FakeMediaElement {
    pause() { this.paused = true; this.fire('pause'); }
  }
  const h = load(HOOK, { HTMLMediaElement: FakeMediaElement });
  const make = () => Object.assign(Object.create(FakeMediaElement.prototype), fakeMedia(), {
    pause: FakeMediaElement.prototype.pause
  });
  const byPage = make();
  h.setMedia([byPage]);
  h.scan();
  h.document.hidden = true;
  FakeMediaElement.prototype.pause.call(byPage);
  assert.ok(h.logs.some((l) => l.includes('media paused while page hidden by=page-script')), h.logs.join('\n'));
  assert.equal(h.status().lastHiddenPause, 'page-script');

  const byBrowser = make();
  h.setMedia([byBrowser]);
  h.scan();
  byBrowser.paused = true;
  byBrowser.fire('pause');
  assert.ok(h.logs.some((l) => l.includes('by=browser')));

  const byApp = make();
  byApp.paused = false;
  h.setMedia([byApp]);
  h.scan();
  h.api().pause();
  assert.ok(h.logs.some((l) => l.includes('by=app')));
});

test('setAppHidden reports the truthful hidden state to the page and child frames', () => {
  // Fake Document whose real visibility (from the engine) stays "visible".
  class FakeDocument {
    get hidden() { return false; }
    get visibilityState() { return 'visible'; }
  }
  const events = [];
  const doc = Object.assign(Object.create(FakeDocument.prototype), {
    documentElement: {},
    listeners: {},
    addEventListener(type, fn) { (this.listeners[type] ||= []).push(fn); },
    dispatchEvent(event) { events.push('document:' + event.type); (this.listeners[event.type] || []).forEach((fn) => fn(event)); },
    hasFocus() { return true; },
    querySelector: () => null,
    querySelectorAll: () => []
  });
  const posted = [];
  const childFrame = { postMessage: (data) => posted.push(data) };
  const h = load(HOOK, {
    Document: FakeDocument,
    frames: [childFrame],
    dispatchEvent: (event) => events.push('window:' + event.type)
  }, doc);

  assert.equal(h.status().visibilityOverrideInstalled, true);
  assert.equal(h.api().setAppHidden(true), true);
  assert.equal(doc.hidden, true);
  assert.equal(doc.visibilityState, 'hidden');
  assert.equal(doc.hasFocus(), false);
  assert.deepEqual(events, ['document:visibilitychange', 'window:blur']);
  assert.equal(JSON.stringify(posted), JSON.stringify([{ __pocketTransposeAppHidden: true }]));
  assert.ok(h.logs.some((l) => l.includes('page hidden')));

  assert.equal(h.api().setAppHidden(false), true);
  assert.equal(doc.hidden, false);
  assert.equal(doc.visibilityState, 'visible');
  assert.deepEqual(events.slice(2), ['document:visibilitychange', 'window:focus']);
});

test('setAppHidden returns false when visibility cannot be overridden', () => {
  const h = load(HOOK);
  assert.equal(h.status().visibilityOverrideInstalled, false);
  assert.equal(h.api().setAppHidden(true), false);
});
