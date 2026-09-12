"use strict";
(() => {
  const $ = id => document.getElementById(id);
  const video = $("video"), status = $("status"), quality = $("quality");
  // Credentials stay in the fragment, which is never sent in HTTP requests/referrers.
  const parameters = new URLSearchParams(location.hash.slice(1));
  const path = parameters.get("path"), token = parameters.get("token");
  const expires = Number(parameters.get("expires"));
  history.replaceState(null, "", location.pathname);
  let attempt = null;
  const valid = /^\/screen-[a-zA-Z0-9-]+\/whep$/.test(path || "") &&
    /^[A-Za-z0-9._~-]+$/.test(token || "") && Number.isFinite(expires) && expires > 0;
  const endpoint = valid ? new URL(path, location.origin) : null;
  const headers = { Authorization: `Bearer ${token}` };
  const current = a => attempt === a && !a.abort.signal.aborted;
  const delta = (value, old) => Number.isFinite(value) && Number.isFinite(old) && value >= old ? value - old : null;

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
    if (a.resource) fetch(a.resource, { method: "DELETE", headers, keepalive: true }).catch(() => {});
    a.resource = null;
  }

  function stop(message = "已停止观看") {
    const old = attempt; attempt = null;
    release(old); video.srcObject = null;
    $("play").disabled = !valid; $("stop").disabled = true;
    status.textContent = message; quality.textContent = "连接后显示实际接收画质";
  }

  function fail(a, message) { if (current(a)) stop(message); }
  function httpError(code) {
    if (code === 401 || code === 403) return "观看链接已失效，请从 App 重新复制。";
    if (code === 404) return "直播已停止或尚未开始。";
    if (code === 400 || code === 406) return "无法协商直播编码，请检查浏览器是否支持 HEVC WebRTC。";
    return `直播服务暂时不可用（${code}），请稍后重试；也可能已达到观看人数上限。`;
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
      if (frames > 0) { a.lastFrameAt = performance.now(); status.textContent = "正在直播"; }
      else if (performance.now() - a.lastFrameAt > 5000) status.textContent = "等待画面，网络或推流可能已中断";
      if (performance.now() - a.lastFrameAt > 20000) fail(a, "长时间未收到可解码画面，请检查推流状态后重新观看。");
    } catch (_) { if (current(a)) quality.textContent = "本次质量采样不可用"; }
    finally { a.sampling = false; }
  }

  async function start() {
    stop();
    if (!valid) { status.textContent = "链接不完整，请从 Purr App 的通话工具复制网页观看链接。"; return; }
    if (Date.now() >= expires) { status.textContent = "观看链接已到期，请从 App 重新复制。"; return; }
    if (!window.RTCPeerConnection || !window.isSecureContext) {
      status.textContent = "此环境无法播放 WebRTC 直播，请使用 HTTPS 和支持 WebRTC 的浏览器。"; return;
    }
    const codecs = window.RTCRtpReceiver?.getCapabilities?.("video")?.codecs || [];
    const hevc = codecs.filter(c => /\/H265$/i.test(c.mimeType));
    if (!hevc.length) { status.textContent = "此浏览器未提供 H.265 WebRTC 解码，请使用支持 HEVC 的浏览器或 Purr App。"; return; }
    const a = { abort: new AbortController(), lastFrameAt: performance.now() };
    attempt = a; $("play").disabled = true; $("stop").disabled = false;
    status.textContent = "正在连接直播…";
    a.timeout = setTimeout(() => fail(a, "连接超时，请检查网络、直播状态或稍后重试。"), 30000);
    try {
      const options = await fetch(endpoint, { method: "OPTIONS", headers, signal: a.abort.signal });
      if (!options.ok) throw new Error(httpError(options.status));
      if (!current(a)) return;
      a.pc = new RTCPeerConnection({ iceServers: iceServers(options.headers.get("Link")) });
      a.pc.ontrack = event => {
        if (!current(a)) return;
        if (!video.srcObject) video.srcObject = new MediaStream();
        video.srcObject.addTrack(event.track);
        video.play().catch(() => { if (current(a)) status.textContent = "已连接，请点击播放器播放。"; });
      };
      a.pc.onconnectionstatechange = () => {
        if (!current(a)) return;
        const state = a.pc.connectionState;
        if (state === "connected") {
          clearTimeout(a.timeout); clearTimeout(a.disconnect);
          status.textContent = "已连接，等待画面…";
        } else if (state === "failed") fail(a, "媒体连接失败，请检查网络后重新观看。");
        else if (state === "disconnected") {
          status.textContent = "网络中断，等待恢复…";
          clearTimeout(a.disconnect);
          a.disconnect = setTimeout(() => fail(a, "连接已断开，点击开始观看重新连接。"), 10000);
        }
      };
      const transceiver = a.pc.addTransceiver("video", { direction: "recvonly" });
      if (!transceiver.setCodecPreferences) throw new Error("此浏览器无法选择 HEVC 编码，请升级浏览器。");
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
      if (!response.ok) throw new Error(httpError(response.status));
      const location = response.headers.get("Location");
      if (!location) throw new Error("直播服务未返回会话地址。");
      const resource = new URL(location, endpoint);
      if (resource.origin !== endpoint.origin || !resource.pathname.startsWith(endpoint.pathname + "/")) throw new Error("直播服务返回了无效的会话地址。");
      a.resource = resource;
      if (!current(a)) { release(a); return; }
      await a.pc.setRemoteDescription({ type: "answer", sdp: await response.text() });
      if (!current(a)) return;
      a.statsTimer = setInterval(() => sample(a), 1000);
    } catch (error) {
      if (current(a) && error.name !== "AbortError") fail(a, error instanceof TypeError ? "无法访问直播服务，请检查网络后重试。" : error.message);
    }
  }
  $("play").onclick = start;
  $("stop").onclick = () => stop();
  $("exit-fullscreen").onclick = () => document.exitFullscreen().catch(() => {});
  $("fullscreen").onclick = async () => {
    try {
      if (document.fullscreenElement) await document.exitFullscreen();
      else if ($("player").requestFullscreen) await $("player").requestFullscreen();
      else if (video.webkitEnterFullscreen) video.webkitEnterFullscreen();
      else status.textContent = "此浏览器不支持全屏播放。";
    } catch (_) { status.textContent = "无法进入全屏，请使用播放器的全屏按钮。"; }
  };
  window.addEventListener("pagehide", () => stop());
  window.addEventListener("hashchange", () => { if (location.hash) location.reload(); });
  document.addEventListener("visibilitychange", () => { if (attempt && !document.hidden) attempt.lastFrameAt = performance.now(); });
  if (!valid) { $("play").disabled = true; status.textContent = "请从 Purr App 的通话工具复制完整的网页观看链接。"; }
})();
