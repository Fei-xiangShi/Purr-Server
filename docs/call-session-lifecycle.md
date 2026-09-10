# Call connection and termination

Business state, local media state, and provider room membership are separate.
The server owns `WAITING -> ACTIVE -> ENDED`; each APK owns its own media
connection and cleanup. A LiveKit connection with one participant is not proof
that the peer has answered.

## Behavior contract

| Situation | APK | Server / LiveKit |
| --- | --- | --- |
| A starts a call | Prepare a session, start foreground service / Telecom / audio, join LiveKit, poll status every 2 seconds | Create a new WAITING call and send `call_started` to B; reject with CONFLICT if an unfinished call exists |
| B answers | Prepare the exact incoming call ID and join its room | Verify access and identity; activate on both participants joining; set the authoritative connection time |
| B rejects before connection | Send `/calls/{id}/end`, consume the local invitation on success | Conditionally end WAITING and enqueue `call_ended` for both users; free the pair for a new call |
| A cancels before connection | End presentation immediately, clean local resources, synchronize `/end` independently | The same conditional WAITING transition ends the invitation |
| Either participant leaves an ACTIVE call | Disconnect only the local media, audio, Telecom and foreground service | `/end` acknowledges local departure; keep ACTIVE while another participant remains |
| One participant remains after connection | Keep the room and microphone controls; show that the peer has left | No single-participant expiry; preserve the call and recording until the remaining person leaves |
| Last participant leaves | Finish local cleanup regardless of server request latency | Empty-room webhook ends the call, stops recording and schedules room deletion |
| Process is killed or final webhook is lost | `onDestroy` and HTTP delivery are not guaranteed | Reconcile provider membership; ACTIVE rooms must be empty for the configured grace period before ending |
| Provider reports the room is missing | End through normal server-status observation | A LiveKit `404 / not_found` is empty inventory; proxy 404, authentication errors and outages are failures, not empty rooms |

Default reconciliation runs every 10 seconds. WAITING invitations expire after
120 seconds; ACTIVE empty-room reconciliation uses a 15-second grace period
starting at the first empty observation. Provider disconnect detection and
worker scheduling add latency. A room with one participant is retained after
connection, regardless of elapsed time.

## Problems corrected

1. `/end` previously acknowledged every request without changing business
   state. This is appropriate for a local ACTIVE departure, but made WAITING
   rejection and cancellation ineffective. It now calls the atomic
   `endIfWaiting` path, preserving ACTIVE if activation wins the race.
2. Android cleared its media generation on termination but still accepted late
   Connected/Reconnected/ParticipantChanged events. These could revive a local
   terminal session. All media events for a terminating or terminal session are
   now ignored.
3. Preparation fetched call status but ignored `ended`. It now rejects those
   obsolete credentials before committing a local session or starting media.
4. Status-driven termination stops its own observer. The complete termination
   handoff now runs in application scope, so cancellation cannot interrupt
   admission of the cleanup operation or its server synchronization.
5. Missing provider rooms were treated as persistent lookup failures. Confirmed
   room absence now permits reconciliation after a process/provider restart.
6. Duplicate terminal events during recording STOPPING could enqueue DELETE_ROOM
   before recording shutdown finished. Termination now rechecks recording
   state and persists any deletion command in the terminal transaction.
7. The APK reused “waiting for answer” after a previously connected peer left.
   The screen now distinguishes this from an unanswered invitation.

## Cleanup ownership and limits

Local presentation ends immediately. Repository cleanup and server end retries
belong to application scope, not the screen or status observer. Native LiveKit
disconnect/release remains detached on the I/O dispatcher; stale callbacks must
not reattach its room. An Android process kill can stop these retries, so the
server must not depend on receiving a final HTTP request.

For ACTIVE calls, HTTP 200 from `/end` does not mean the shared call ended. The
last participant must actually disappear from LiveKit, or the provider must
report the room finished/missing. A still-present peer is intentionally kept.
Provider outages defer this decision instead of terminating a possibly live call.

Recording STOP and room DELETE commands remain durable and retryable. Waiting
call room deletion is recovered by the ended-call cleanup worker. Business
termination frees the pair independently of these provider operations.

## Validation

- Android repository regressions reproduce late-event revival and preparation
  of an already-ended call, then assert terminal state and no media acquisition.
- Existing repository tests cover concurrent termination, caller cancellation,
  slow server synchronization, local cleanup timeout and resource ownership.
- HTTP tests cover caller cancellation, callee rejection, duplicate `/end`,
  stale incoming IDs and creation of a fresh call.
- Realtime tests assert `call_ended` delivery and removal of the incoming candidate.
- Room tests preserve ACTIVE with one participant and end only after everyone
  leaves; duplicate recording shutdown cannot schedule early room deletion.
- Adapter tests distinguish provider-confirmed absence from proxy/provider errors.

Device follow-up: test rejection, cancellation while connecting, both orders of
ACTIVE departure, and killing the last APK process. Compare APK `CallLifecycle`
logs with the call ID, provider membership and server status; local JVM tests do
not verify vendor-specific Android background behavior or native RTC shutdown.

## In-call UI and screen-media audit (2026-09-11)

- Local call termination freezes the locally displayed duration without marking
  the peer's retained room ended. Muted, interrupted, reconnecting and departed
  participants do not show activity animations from residual PCM samples.
- Preparation/connection failures remain visible until explicit dismissal;
  repository failures no longer auto-navigate as though the app had crashed.
- Hanging up or receiving Terminating/terminal state clears pending capture
  permission, OBS setup and remote playback. A late share-create response is
  compensated, never shown in an ended call. Duplicate creation taps share one
  pending operation.
- Screen cleanup for one call does not wait behind another call's HTTP cleanup.
  Rejoining the same business call resets the cleanup boundary for the new local
  attempt.
- Only the publishing user can stop the server share through the public DELETE
  endpoint. The peer's departure leaves the owner's mobile/OBS stream intact.
  Internal shared-call termination can still revoke all associated screen media.
- DELETE accepts an optional `expectedShareId`; Android supplies it whenever
  known. The store checks it inside the stop transaction so delayed failures or
  retries cannot stop a replacement stream. Existing callers without the query
  parameter remain compatible.
- Provider creation verifies the call under its database lock and rechecks
  state after path provisioning. A concurrent stop never returns fresh usable
  publishing credentials. Provider cleanup failure remains STOPPING and retryable
  instead of falsely reporting successful cleanup.
- Publisher callbacks, service starts/stops, and local status updates are scoped
  to the current share. Late success cannot revive STOPPING, and stopping a
  pending permission request does not start an unnecessary background service.
- WHEP playback has a separate attempt owner. Cancellation aborts pending HTTP,
  teardown runs off the UI path, stale callbacks/frames cannot affect replacement
  playback, and identical credentials can be used for a fresh explicit attempt.
  Initial buffering is published before media can arrive; first-frame and
  connection timeouts cannot leave an indefinite initial spinner. Native failure
  detaches and cleans the failed session.
- First-frame dimensions drive the remote panel's aspect ratio and the panel is
  constrained above the call controls. Publisher downscaling uses one uniform
  scale, including unusually tall screens. Playback failure remains visible
  across server LIVE refreshes rather than reverting to a false connecting state.
- Uplink packet loss uses lost / sent, since sent already includes lost packets.
  Downlink uses lost / (received + lost); missing stats remain unknown and invalid
  counters are bounded. Transport diagnostics prefer the selected successful ICE
  candidate pair instead of an arbitrary unselected pair.

The audit preserves LiveKit microphone/communication-audio ownership. Screen
publishing uses playback capture, and WHEP uses media audio with receive-only
tracks. Device testing remains necessary for actual capture permissions, hardware
codecs, rotation, speaker/headset/Bluetooth mixing, packet loss and rendered frames.

## Outgoing admission and incoming recovery (2026-09-11)

- A new outgoing request has no `expectedCallId` and must create a fresh call.
  The pair-row lock serializes competing requests; only one succeeds, while the
  other gets CONFLICT. Existing rooms are never deleted to make room for a new
  outgoing request. Explicit incoming joins continue to require the exact ID.
- The APK also verifies `createdByRequest` before connecting a new outgoing
  call, protecting deployments where the server still implements reuse. It
  does not compensate/end a room it did not create.
- Home blocks outgoing navigation while a pending incoming candidate exists.
  Incoming prompt actions carry their rendered call ID so a stale click cannot
  accept or decline a replacement call.
- Cold-start incoming UI waits for account/server recovery before its missing-
  candidate dismissal timer begins. Realtime call revisions reject late HTTP
  snapshots; ended IDs also reject obsolete websocket snapshots.
- Telecom rejects a different call ID while active or preparing. Explicit
  disconnect cancels matching pending registration. Detached endpoint updates
  and late closure cannot reset the replacement call's audio route.
- The call surface has four controls: microphone, audio route, tools, hangup.
  Tools opens a bottom sheet for screen sharing/OBS and network diagnostics.
  Ending/failed screens dismiss and disable tools; explicit error exit remains
  available. Existing share start/stop behavior remains behind the sheet.

Release candidate: Android 0.2.14 (versionCode 25). Local automated validation
and signed artifact verification do not substitute for two-device Android/FCM,
Telecom interruption, MediaProjection, and OBS testing. Server code must be
rolled out separately for the revised rejection and room lifecycle behavior.
