# Screen sharing deployment

Purr uses MediaMTX only as the screen-program media plane:

- OBS and Android publish through WHIP.
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

MediaMTX uses readTimeout: 7s. Purr reconciles once per second and requires two
consecutive successful complete snapshots before converting a live share to
stopped. Provider errors never synthesize a stopped state.

## OBS

1. Start or join the Purr call so its server state is active.
2. Create an OBS screen share through POST /calls/{callId}/screen-share with
   {"source":"obs"}.
3. In OBS, choose WHIP and copy publishing.whip.url into the server field and
   publishing.whip.bearerToken into the Bearer Token field.
4. Prefer H.264 video and Opus audio for compatibility.
5. If WHIP is unavailable, use the returned publishing.srt.url; it already
   contains the scoped token, 1316-byte packet size and per-share passphrase.

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
