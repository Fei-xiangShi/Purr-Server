# Screen sharing deployment

Purr uses MediaMTX only as the screen-program media plane:

- Android publishes H.265/HEVC Main (8-bit) and Opus through WHIP.
- OBS publishes the same codecs through WHIP when supported, or encrypted SRT.
- Android viewers play through WHEP.
- OBS may fall back to encrypted SRT.
- Voice conversation remains in LiveKit.
- HLS, RTMP and RTSP are disabled.

## Public endpoints and firewall

Create DNS for PURR_STREAM_DOMAIN and point it at the server. Expose:

| Port | Transport | Purpose |
|---|---|---|
| 443 | TCP | Caddy TLS termination for WHIP/WHEP signaling |
| 8189 | UDP | Direct MediaMTX WebRTC ICE candidate |
| 8890 | UDP | Encrypted OBS SRT fallback |

Do not expose MediaMTX ports 8889, 9997 or 9998. They are private Compose
listeners for signaling proxying, the Control API and metrics.

The local debug override additionally advertises MediaMTX's Compose-network
address, allowing a containerized OBS/FFmpeg publisher to complete ICE while
Android emulators continue to use the explicit `10.0.2.2` candidate.

The API domain returns 404 for /internal and /internal/*; only MediaMTX on the
private Compose network calls /internal/mediamtx/auth.

## TURN

Set all MEDIAMTX_TURN_* variables. The credentials must work for a generic ICE
client. LiveKit embedded TURN credentials are generated through LiveKit
signaling and cannot simply be copied into MediaMTX.

For a coturn-compatible TURN REST shared secret:

    MEDIAMTX_TURN_URL=turns:turn.example.com:5349?transport=tcp
    MEDIAMTX_TURN_USERNAME=AUTH_SECRET
    MEDIAMTX_TURN_PASSWORD=<turn-rest-shared-secret>

MEDIAMTX_WEBRTC_PUBLIC_HOST is the public IP or DNS name advertised for direct
UDP 8189 connectivity. TURN remains the fallback for symmetric NAT, CGNAT and
networks that block the direct candidate.

## Start and validate

Copy .env.example to .env, replace every placeholder, then run:

    docker compose --env-file .env config -q
    docker compose --profile bundled-proxy --env-file .env up -d --build

MediaMTX uses readTimeout: 7s. Purr reconciles once per second, tracks inbound
media-byte progress to catch WebRTC sessions whose ICE failure is reported late,
and requires two consecutive successful complete missing snapshots before converting a live share to
stopped. Provider errors never synthesize a stopped state.

## OBS

1. Start or join the Purr call so its server state is active.
2. Create an OBS screen share through POST /calls/{callId}/screen-share with
   {"source":"obs"}.
3. If OBS offers H.265/HEVC for WHIP, choose WHIP and copy publishing.whip.url
   into the server field and publishing.whip.bearerToken into the Bearer Token
   field. Select HEVC Main (8-bit) hardware encoding and Opus audio.
4. Otherwise, choose Advanced Output, Recording, Custom Output (FFmpeg), output
   to URL, MPEG-TS, HEVC hardware video and Opus audio. Use the returned
   publishing.srt.url and start this recording output to send the live stream.
   The URL already contains the scoped token, 1316-byte packet size and per-share
   passphrase. Do not substitute AAC: WHEP program playback requires supported
   WebRTC audio such as Opus.
5. Use the selected resolution/FPS/CBR bitrate from Purr, a 1-second keyframe
   interval, 0 B-frames and no look-ahead. Prefer low-latency encoder settings.

Android 0.2.15 viewers require H.265 hardware decoding and do not fall back to
software decoding or H.264. Unsupported devices show a screen-share error while
the voice call continues. Codec support does not guarantee every resolution/FPS;
validate the chosen profile on both devices before deployment.

Never place media tokens, SDP, TURN passwords or SRT passphrases in logs.

## API behavior

- Only participants in an active call can create or view a share.
- A call has at most one authorized/live share.
- Publish credentials belong only to the owner who created the share.
- Each viewer receives a fresh WHEP read token.
- Stopping the share, ending the call or reaching the share TTL immediately
  makes HTTP authentication reject both publisher and viewer tokens.
- Provider cleanup is retried until WebRTC/SRT sessions and the dynamic path
  are gone; MediaMTX failure does not end the LiveKit voice call.
# Browser viewing and diagnostics (0.1.3 / Android 0.2.16)

The Android call tools now offer **网页观看直播 → 复制链接**. The link uses the
current READ credential, whose default admission lifetime is five minutes; the
copy confirmation shows the actual expiry. It never includes WHIP/SRT publishing
credentials. Treat the copied link as access to the stream. Tokens are carried
in the URL fragment and removed from browser history when the player loads.
Refreshing the page requires reopening the original complete link. Once admitted,
a WHEP connection can continue until the stream ends; token expiry prevents new
connections, and stopping the share removes the media path and existing readers.

`https://<stream-domain>/watch` serves the player; Caddy routes `/watch` and
`/watch.js` to purr-server. Media signaling remains on the same MediaMTX origin.
The page receives HEVC video and program audio over WHEP, without joining the
LiveKit voice room or requesting microphone/camera permissions. It requires a
browser/device that exposes H.265 in WebRTC capabilities; there is no transcoding
or viewer-side quality selector. Autoplay is muted; the viewer can unmute using
the native player controls. Fullscreen fills the viewport with aspect-preserving
cropping. An unsupported browser gets an explicit compatibility message.

Dynamic media paths now allow eight readers total, including Android and browser
viewers. Existing paths keep their original reader limit until the share restarts.
Each extra viewer consumes another copy of the stream's server egress bandwidth.

Deploy the rebuilt **purr-server 0.1.3**, updated **Caddyfile**, and updated
**MediaMTX configuration**, then install **Android 0.2.16 (27)**. Recreate/reload
these Compose services using the deployment's normal workflow. No database
migration, media codec change or LiveKit update is required for this increment.
The server also now allows either participant to rejoin the exact unfinished
call ID. New unqualified outgoing requests still conflict when a room exists.

Quality diagnostics use interval counters and distinguish unavailable values
from zero. Publisher FPS comes from encoder frame callbacks, never RTP packet
counts; sender queues/bytes do not prove remote delivery. Receiver statistics
include decoded FPS, video bitrate/loss, jitter, decode time, jitter-buffer wait,
freeze count and decoder identity when supplied by WebRTC. OBS sender metrics
remain in OBS; its receiving peer sees measured playback metrics. End-to-end
glass-to-glass delay is not inferred from RTT or buffer time.

Validation: Gradle backend tests and architecture checks; Android unit tests,
lint and builds; `node --test src/test/js/watch.test.cjs` for player lifecycle
tests. Real devices and a live MediaMTX deployment are required to verify HEVC
hardware behavior, TURN traversal and actual latency under load.
