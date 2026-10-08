const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const script = fs.readFileSync(path.join(__dirname, '../../main/resources/web/watch.js'), 'utf8');

function harness({ expired = false, hevc = true, share = false, resource = share ? '/screen-share-test/whep/session' : '/screen-test/whep/session', fetchOverride } = {}) {
  const nodes = new Map();
  const node = id => {
    if (!nodes.has(id)) nodes.set(id, { textContent: '', disabled: false, srcObject: null, play: async () => {} });
    return nodes.get(id);
  };
  const calls = [], peers = [], timers = new Map();
  let timerId = 0;
  const location = { pathname: share ? '/watch/share-test' : '/watch', origin: 'https://stream.test', hash: share ? '' : `#path=%2Fscreen-test%2Fwhep&token=read-only-token&expires=${expired ? 1 : Date.now() + 300_000}` };
  class Peer {
    constructor(options) { this.options = options; this.iceGatheringState = 'complete'; this.transceivers = []; peers.push(this); }
    addTransceiver(kind, options) {
      const t = { kind, options, setCodecPreferences: codecs => { t.codecs = codecs; } }; this.transceivers.push(t); return t;
    }
    async createOffer() { return { type: 'offer', sdp: 'test-offer' }; }
    async setLocalDescription(offer) { this.localDescription = offer; }
    async setRemoteDescription(answer) { this.answer = answer; }
    addEventListener() {} removeEventListener() {}
    close() { this.closed = true; }
  }
  const response = (code, location) => ({ ok: code >= 200 && code < 300, status: code,
    headers: { get: key => key === 'Location' ? location : null }, text: async () => 'test-answer' });
  const fetch = async (url, options) => {
    calls.push({ url: String(url), ...options });
    if (fetchOverride) return fetchOverride(url, options, response);
    if (options.method === 'GET') return { ...response(200), json: async () => ({ url: 'https://stream.test/screen-share-test/whep', bearerToken: 'fresh-read-token' }) };
    return response(options.method === 'POST' ? 201 : 204, resource);
  };
  vm.runInNewContext(script, {
    document: { getElementById: node, addEventListener() {}, hidden: false }, location,
    history: { replaceState() { location.hash = ''; } },
    window: { RTCPeerConnection: Peer, RTCRtpReceiver: { getCapabilities: () => ({ codecs: hevc ? [{ mimeType: 'video/H265' }] : [] }) }, isSecureContext: true, addEventListener() {} },
    RTCPeerConnection: Peer, URL, URLSearchParams, AbortController, DOMException, fetch, Date,
    performance: { now: () => 1000 },
    setTimeout: fn => { timers.set(++timerId, fn); return timerId; },
    clearTimeout: id => timers.delete(id), setInterval: fn => { timers.set(++timerId, fn); return timerId; },
    clearInterval: id => timers.delete(id),
  });
  return { node, calls, peers, location };
}

const settled = () => new Promise(resolve => setImmediate(resolve));

test('share URL autoplays without login or fragment and remains refreshable', async () => {
  const h = harness({ share: true }); await settled();
  assert.equal(h.calls[0].url, 'https://stream.test/watch/share-test/playback');
  assert.equal(h.calls[0].credentials, 'omit');
  assert.equal(h.calls[0].cache, 'no-store');
  assert.equal(h.calls[0].headers, undefined);
  assert.equal(h.location.pathname, '/watch/share-test');
  assert.equal(h.peers[0].answer.sdp, 'test-answer');
  assert.equal(h.calls.find(c => c.method === 'POST').headers.Authorization, 'Bearer fresh-read-token');
  const refreshed = harness({ share: true }); await settled();
  assert.equal(refreshed.peers[0].answer.sdp, 'test-answer');
});

test('retry refreshes credentials while old session cleanup uses its own token', async () => {
  let issued = 0;
  const h = harness({ share: true, fetchOverride: (_, options, response) => options.method === 'GET'
    ? { ...response(200), json: async () => ({ url: 'https://stream.test/screen-share-test/whep', bearerToken: `read-${++issued}` }) }
    : response(201, '/screen-share-test/whep/session') });
  await settled(); await h.node('play').onclick();
  assert.equal(issued, 2);
  assert.equal(h.calls.find(c => c.method === 'DELETE').headers.Authorization, 'Bearer read-1');
  assert.equal(h.calls.filter(c => c.method === 'POST').at(-1).headers.Authorization, 'Bearer read-2');
});

test('ended share needs no account and gives an ended message without signaling', async () => {
  const h = harness({ share: true, fetchOverride: (_, __, response) => response(404) });
  await settled();
  assert.equal(h.peers.length, 0); assert.match(h.node('status').textContent, /已停止/);
  assert.deepEqual(h.calls.map(c => c.method), ['GET']);
});

test('stop during link resolution ignores late credentials', async () => {
  let complete;
  const h = harness({ share: true, fetchOverride: (_, __, response) => new Promise(resolve => {
    complete = () => resolve({ ...response(200), json: async () => ({ url: 'https://stream.test/screen-share-test/whep', bearerToken: 'late' }) });
  }) });
  h.node('stop').onclick(); complete(); await settled();
  assert.equal(h.peers.length, 0); assert.equal(h.calls.length, 1);
  assert.match(h.node('status').textContent, /已停止/);
});

test('resolved foreign media URL never receives read credentials', async () => {
  const h = harness({ share: true, fetchOverride: (_, __, response) => ({
    ...response(200), json: async () => ({ url: 'https://other.test/screen-share-test/whep', bearerToken: 'private' }),
  }) });
  await settled();
  assert.equal(h.calls.length, 1); assert.equal(h.peers.length, 0);
  assert.match(h.node('status').textContent, /无效/);
});

test('expired link never starts signaling', async () => {
  const h = harness({ expired: true }); await h.node('play').onclick();
  assert.equal(h.calls.length, 0); assert.match(h.node('status').textContent, /到期/);
});
test('unsupported HEVC is actionable and never allocates a peer', async () => {
  const h = harness({ hevc: false }); await h.node('play').onclick();
  assert.equal(h.peers.length, 0); assert.match(h.node('status').textContent, /H.265/);
});
test('read-only WHEP uses HEVC and releases the exact session on stop', async () => {
  const h = harness(); assert.equal(h.location.hash, ''); await h.node('play').onclick();
  assert.equal(h.peers[0].transceivers.length, 2);
  for (const t of h.peers[0].transceivers) assert.equal(t.options.direction, 'recvonly');
  assert.equal(h.peers[0].transceivers[0].codecs[0].mimeType, 'video/H265');
  assert.equal(h.peers[0].answer.sdp, 'test-answer');
  for (const request of h.calls) assert.equal(request.headers.Authorization, 'Bearer read-only-token');
  h.node('stop').onclick();
  assert.equal(h.peers[0].closed, true);
  assert.equal(h.calls.at(-1).method, 'DELETE');
  assert.equal(h.calls.at(-1).url, 'https://stream.test/screen-test/whep/session');
});
test('foreign session URL is rejected without leaking credentials', async () => {
  const h = harness({ resource: 'https://other.test/stolen' }); await h.node('play').onclick();
  assert.match(h.node('status').textContent, /无效/);
  assert.equal(h.peers[0].closed, true);
  assert.ok(h.calls.every(call => call.url.startsWith('https://stream.test/')));
});
test('stop during discovery prevents late network response from reopening playback', async () => {
  let complete;
  const h = harness({ fetchOverride: (_, __, response) => new Promise(resolve => { complete = () => resolve(response(204)); }) });
  const pending = h.node('play').onclick(); h.node('stop').onclick(); complete(); await pending;
  assert.equal(h.peers.length, 0); assert.match(h.node('status').textContent, /已停止/);
});
test('expired authorization is distinguished from server failure', async () => {
  const h = harness({ fetchOverride: (_, __, response) => response(401) }); await h.node('play').onclick();
  assert.match(h.node('status').textContent, /重新复制/); assert.equal(h.peers.length, 0);
});
