# Call room cleanup and Docker ports

Docker `ports` in `compose.yaml` publish the LiveKit/HTTP/RTC listeners for the
running containers. They remain open for as long as the containers are running;
an open listener is not evidence that a historical LiveKit room is still alive.
Use `docker compose ps` and the LiveKit room API separately when diagnosing the
two lifecycles. `docker compose down` stops the listeners; it is not the normal
per-call cleanup operation.

Per-call cleanup is keyed by `callId` and follows this order:

1. End the business call and persist the recording stop command (or a room
   delete command when recording was never started).
2. Stop egress and accept its reliable terminal result (`STOPPED` or `FAILED`).
3. Persist a `DELETE_ROOM` command only after that terminal result. The
   background dispatcher then calls the provider-neutral room terminator. The LiveKit adapter invokes the
   official Room Service `deleteRoom(roomName)` operation, treating room-not-
   found as already cleaned.

`DELETE_ROOM` uses the same durable command table, idempotency key, lease,
backoff and startup reconciliation as recording commands. A provider failure
therefore remains retryable across webhook duplicates and server restarts. Room
deletion never gates admission of a new call; every call owns its room and its
cleanup commands independently.
