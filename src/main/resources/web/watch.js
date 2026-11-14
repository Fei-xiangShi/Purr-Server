"use strict";
(() => {
  const $ = id => document.getElementById(id);
  const video = $("video"), status = $("status"), quality = $("quality");
  const player = $("player"), badge = $("badge"), unmute = $("unmute");
  // New links resolve fresh READ credentials on every attempt. Keep old app links working.
  const sharePath = /^\/watch\/share-[a-zA-Z0-9-]+\/?$/.test(location.pathname)
    ? location.pathname.replace(/\/$/, "") : null;
  const parameters = new URLSearchParams(location.hash.slice(1));
  const path = parameters.get("path"), token = parameters.get("token");
  const expires = Number(parameters.get("expires"));
  history.replaceState(null, "", location.pathname);
  let attempt = null, retryTimer = null, retries = 0;
  const legacyValid = /^\/screen-[a-zA-Z0-9-]+\/whep$/.test(path || "") &&
    /^[A-Za-z0-9._~-]+$/.test(token || "") && Number.isFinite(expires) && expires > 0;
  const valid = Boolean(sharePath || legacyValid);
  const current = a => attempt === a && !a.abort.signal.aborted;
  const delta = (value, old) => Number.isFinite(value) && Number.isFinite(old) && value >= old ? value - old : null;
  // Share links can always mint fresh credentials, so transient failures are retried
  // automatically. Legacy fragment links carry one expiring token and never retry.
  const MAX_RETRIES = 40;
  const problem = (message, retry = false) => Object.assign(new Error(message), { retry: retry && Boolean(sharePath) });
  const BADGES = { live: "LIVE", connecting: "连接中", waiting: "重连中", idle: "未播放", error: "已中断" };

  function setState(state, message) {
    player.dataset.state = state;
    badge.dataset.state = state;
    badge.textContent = BADGES[state] || BADGES.idle;
    status.textContent = message;
    syncUnmute();
  }

  function syncUnmute() {
    unmute.hidden = !(player.dataset.state === "live" && video.muted === true);
  }

  function iceServers(link) {
    if (!link) return [];
    return [...link.matchAll(/<([^>]+)>\s*;\s*rel="ice-server"([^,]*)/g)].map(match => {
      const server = { urls: match[1] };
      const username = /;\s*username="((?:[^"\\]|\\.)*)"/.exec(match[2]);
      const credential = /;\s*credential="((?:[^"\\]|\\.)*)"/.exec(match[2]);
      if (username) server.username = JSON.parse(`"${username[1]}"`);
      if (credential) server.credential = JSON.parse(`"${credential[1]}"`);
      return server;
    });
  }

  function release(a) {
    if (!a) return;
    a.abort.abort();
    clearTimeout(a.timeout); clearTimeout(a.disconnect); clearInterval(a.statsTimer);
    a.pc?.close();
    if (a.resource) fetch(a.resource, { method: "DELETE", headers: a.headers, keepalive: true }).catch(() => {});
    a.resource = null;
  }

  function teardown() {
    const old = attempt; attempt = null;
    clearTimeout(retryTimer); retryTimer = null;
    release(old); video.srcObject = null;
    quality.textContent = "连接后显示实际接收画质";
  }

  function stop(message = "已停止观看", state = "idle") {
    teardown(); retries = 0;
    $("play").disabled = !valid; $("stop").disabled = true;
    setState(state, message);
  }

  function retryLater(message) {
    teardown();
    if (++retries > MAX_RETRIES) { stop("多次重试仍无法连接，请确认直播仍在进行后点击开始观看。", "error"); return; }
    const wait = Math.min(15000, 2000 * 2 ** Math.min(retries - 1, 3));
    // "开始观看" retries immediately; "停止观看" cancels the pending retry.
    $("play").disabled = false; $("stop").disabled = false;
    setState("waiting", `${message} ${Math.round(wait / 1000)} 秒后自动重试…`);
    retryTimer = setTimeout(() => { retryTimer = null; start(true); }, wait);
  }

  function fail(a, message, retry = false) {
    if (!current(a)) return;
    if (retry && sharePath) retryLater(message); else stop(message, "error");
  }

  function playbackError(code) {
    if (code === 404) return problem("直播已停止或链接无效。");
    if (code === 429 || code >= 500) return problem(`直播服务暂时不可用（${code}）。`, true);
    return problem(`无法获取播放信息（${code}），请稍后重试。`);
  }

  function httpError(code) {
    // A fresh share token is rejected only once the share or call has ended;
    // the retry resolves that through the playback endpoint.
    if (code === 401 || code === 403) return problem(sharePath ? "暂时无法连接。" : "观看链接已失效，请从 App 重新复制。", true);
    if (code === 404) return problem(sharePath ? "主播尚未开始推流或正在重连。" : "直播已停止或尚未开始。", true);
    if (code === 400 || code === 406) return problem("无法协商直播编码，请检查浏览器是否支持 HEVC WebRTC。");
    return problem(`直播服务暂时不可用（${code}），请稍后重试；也可能已达到观看人数上限。`, true);
  }

  async function sample(a) {
    if (!current(a) || a.sampling || document.hidden) return;
    a.sampling = true;
    try {
      const report = await a.pc.getStats();
      if (!current(a)) return;
      const stream = [...report.values()].find(s => s.type === "inbound-rtp" && s.kind === "video");
      if (!stream) return;
      const old = a.previous?.id === stream.id ? a.previous : null;
      a.previous = stream;
      const seconds = old && (stream.timestamp - old.timestamp) / 1000;
      const frames = delta(stream.framesDecoded, old?.framesDecoded);
      const bytes = delta(stream.bytesReceived, old?.bytesReceived);
      const fps = seconds > 0 && frames !== null ? `${(frames / seconds).toFixed(1)} fps` : "帧率采样中";
      const bitrate = seconds > 0 && bytes !== null ? `${(bytes * 8 / seconds / 1e6).toFixed(2)} Mbps` : "码率采样中";
      const lost = delta(stream.packetsLost, old?.packetsLost), received = delta(stream.packetsReceived, old?.packetsReceived);
      const loss = lost !== null && received !== null && lost + received > 0 ? ` · 区间丢包 ${(lost * 100 / (lost + received)).toFixed(1)}%` : "";
      quality.textContent = `${stream.frameWidth || "—"} × ${stream.frameHeight || "—"} · ${fps} · ${bitrate}${loss}`;
      if (frames > 0) {
        a.lastFrameAt = performance.now(); retries = 0;
        if (player.dataset.state !== "live" || status.textContent !== "正在直播") setState("live", "正在直播");
      } else if (performance.now() - a.lastFrameAt > 5000) setState("connecting", "等待画面，网络或推流可能已中断");
      if (performance.now() - a.lastFrameAt > 20000) fail(a, "长时间未收到可解码画面。", true);
    } catch (_) { if (current(a)) quality.textContent = "本次质量采样不可用"; }
    finally { a.sampling = false; }
  }

  async function start(auto = false) {
    if (auto !== true) retries = 0;
    teardown();
    $("play").disabled = !valid; $("stop").disabled = true;
    if (!valid) { setState("error", "链接不完整，请从 Purr 通话页面复制观看链接。"); return; }
    if (!sharePath && Date.now() >= expires) { setState("error", "观看链接已到期，请从 App 重新复制。"); return; }
    if (!window.RTCPeerConnection || !window.isSecureContext) {
      setState("error", "此环境无法播放 WebRTC 直播，请使用 HTTPS 和支持 WebRTC 的浏览器。"); return;
    }
    const codecs = window.RTCRtpReceiver?.getCapabilities?.("video")?.codecs || [];
    const hevc = codecs.filter(c => /\/H265$/i.test(c.mimeType));
    if (!hevc.length) { setState("error", "此浏览器未提供 H.265 WebRTC 解码，请使用支持 HEVC 的浏览器或 Purr App。"); return; }
    const a = { abort: new AbortController(), lastFrameAt: performance.now() };
    attempt = a; $("play").disabled = true; $("stop").disabled = false;
    setState("connecting", retries > 0 ? "正在重新连接直播…" : "正在连接直播…");
    a.timeout = setTimeout(() => fail(a, "连接超时。", true), 30000);
    try {
      let endpoint = legacyValid ? new URL(path, location.origin) : null;
      let readToken = token;
      if (sharePath) {
        const playback = await fetch(new URL(`${sharePath}/playback`, location.origin), {
          method: "GET", cache: "no-store", credentials: "omit", signal: a.abort.signal,
        });
        if (!current(a)) return;
        if (!playback.ok) throw playbackError(playback.status);
        const credentials = await playback.json();
        if (!current(a)) return;
        endpoint = new URL(credentials.url);
        if (endpoint.origin !== location.origin ||
            endpoint.pathname !== `/screen-${sharePath.split("/").pop()}/whep` || endpoint.search || endpoint.hash) {
          throw problem("直播服务返回了无效的播放地址。");
        }
        readToken = credentials.bearerToken;
        if (!/^[A-Za-z0-9._~-]+$/.test(readToken || "")) throw problem("无法获取播放信息，请重试。");
      }
      const headers = a.headers = { Authorization: `Bearer ${readToken}` };
      const options = await fetch(endpoint, { method: "OPTIONS", headers, signal: a.abort.signal });
      if (!options.ok) throw httpError(options.status);
      if (!current(a)) return;
      a.pc = new RTCPeerConnection({ iceServers: iceServers(options.headers.get("Link")) });
      a.pc.ontrack = event => {
        if (!current(a)) return;
        if (!video.srcObject) video.srcObject = new MediaStream();
        video.srcObject.addTrack(event.track);
        video.play().catch(() => { if (current(a)) setState("connecting", "已连接，请点击播放器播放。"); });
      };
      a.pc.onconnectionstatechange = () => {
        if (!current(a)) return;
        const state = a.pc.connectionState;
        if (state === "connected") {
          clearTimeout(a.timeout); clearTimeout(a.disconnect);
          a.lastFrameAt = performance.now();
          setState("connecting", "已连接，等待画面…");
        } else if (state === "failed") fail(a, "媒体连接失败。", true);
        else if (state === "disconnected") {
          setState("connecting", "网络中断，等待恢复…");
          clearTimeout(a.disconnect);
          a.disconnect = setTimeout(() => fail(a, "连接已断开。", true), 10000);
        }
      };
      const transceiver = a.pc.addTransceiver("video", { direction: "recvonly" });
      if (!transceiver.setCodecPreferences) throw problem("此浏览器无法选择 HEVC 编码，请升级浏览器。");
      transceiver.setCodecPreferences(hevc);
      a.pc.addTransceiver("audio", { direction: "recvonly" });
      await a.pc.setLocalDescription(await a.pc.createOffer());
      await new Promise((resolve, reject) => {
        const done = () => { clearTimeout(timer); a.pc.removeEventListener("icegatheringstatechange", changed); a.abort.signal.removeEventListener("abort", aborted); resolve(); };
        const changed = () => { if (a.pc.iceGatheringState === "complete") done(); };
        const aborted = () => { done(); reject(new DOMException("Stopped", "AbortError")); };
        const timer = setTimeout(done, 8000);
        a.pc.addEventListener("icegatheringstatechange", changed);
        a.abort.signal.addEventListener("abort", aborted, { once: true });
        changed();
      });
      if (!current(a)) return;
      const response = await fetch(endpoint, {
        method: "POST", headers: { ...headers, "Content-Type": "application/sdp", Accept: "application/sdp" },
        body: a.pc.localDescription.sdp, signal: a.abort.signal,
      });
      if (!response.ok) throw httpError(response.status);
      const resourceLocation = response.headers.get("Location");
      if (!resourceLocation) throw problem("直播服务未返回会话地址。");
      const resource = new URL(resourceLocation, endpoint);
      if (resource.origin !== endpoint.origin || !resource.pathname.startsWith(endpoint.pathname + "/")) throw problem("直播服务返回了无效的会话地址。");
      a.resource = resource;
      if (!current(a)) { release(a); return; }
      await a.pc.setRemoteDescription({ type: "answer", sdp: await response.text() });
      if (!current(a)) return;
      a.statsTimer = setInterval(() => sample(a), 1000);
    } catch (error) {
      if (!current(a) || error.name === "AbortError") return;
      // fetch() rejects with TypeError when the network is unreachable.
      if (error.name === "TypeError") fail(a, "无法访问直播服务，请检查网络。", true);
      else fail(a, error.message, error.retry === true);
    }
  }

  $("play").onclick = () => start();
  $("stop").onclick = () => stop();
  unmute.onclick = () => { video.muted = false; video.play?.()?.catch?.(() => {}); syncUnmute(); };
  $("fit").onclick = () => {
    const cover = player.dataset.fit !== "cover";
    player.dataset.fit = cover ? "cover" : "contain";
    $("fit").textContent = cover ? "完整显示" : "裁切填满";
  };
  $("exit-fullscreen").onclick = () => document.exitFullscreen().catch(() => {});
  $("fullscreen").onclick = async () => {
    try {
      if (document.fullscreenElement) await document.exitFullscreen();
      else if (player.requestFullscreen) await player.requestFullscreen();
      else if (video.webkitEnterFullscreen) video.webkitEnterFullscreen();
      else status.textContent = "此浏览器不支持全屏播放。";
    } catch (_) { status.textContent = "无法进入全屏，请使用播放器的全屏按钮。"; }
  };
  video.addEventListener("volumechange", syncUnmute);
  // Match the box to the stream so a portrait phone share is not letterboxed into 16:9.
  video.addEventListener("resize", () => {
    if (video.videoWidth > 0 && video.videoHeight > 0) player.style.setProperty("--ar", (video.videoWidth / video.videoHeight).toFixed(4));
  });
  window.addEventListener("pagehide", () => stop());
  // A page restored from the back/forward cache was stopped by pagehide.
  window.addEventListener("pageshow", event => { if (event.persisted && sharePath && !attempt) start(); });
  window.addEventListener("online", () => { if (retryTimer) start(true); });
  window.addEventListener("hashchange", () => { if (location.hash) location.reload(); });
  document.addEventListener("visibilitychange", () => { if (attempt && !document.hidden) attempt.lastFrameAt = performance.now(); });
  if (!valid) { $("play").disabled = true; setState("error", "请从 Purr 通话页面复制完整的观看链接。"); }
  else if (sharePath) start();
  else setState("idle", "准备就绪");
})();
