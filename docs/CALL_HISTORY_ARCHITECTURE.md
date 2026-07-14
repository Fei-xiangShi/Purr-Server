# Call history architecture

The history feature is split into independent application ports:

- `CallCalendarQueryService` returns date buckets for a bounded epoch range and an explicit IANA time zone.
- `CallHistoryQueryService` returns a paged day projection. It derives direction and outcome without exposing provider identifiers.
- `CallDetailQueryService` returns the immutable call timeline, recording summary, and a quality aggregate.
- `CallTelemetryService` validates and idempotently stores client quality samples while a call is active.

The Android feature mirrors these boundaries with separate calendar, day, detail, recording, and transcription repositories. Navigation only passes a date or call id. A detail screen emits a signed recording URL request; the application owns `DownloadManager`, so the feature module has no Android storage dependency.

## Collected call data

The call session is the source of truth for requested, connected, and ended timestamps. The server derives ringing duration, conversation duration, caller direction, and one of `completed`, `missed`, or `cancelled`. Recording status and object metadata remain behind `CallRecordingStore` and `RecordingDownloadProvider` ports.

During a connected call the Android process reports one sample every 15 seconds. A sample may contain round-trip time, jitter, uplink/downlink bitrate, uplink/downlink packet loss, codec names, network transport, validated-network state, and metered-network state. No audio, transcript, IP address, or raw provider payload is stored. Duplicate samples are ignored by `(call_id, user_id, sampled_at_epoch_millis)`.

Quality rows are scoped to the call and are deleted with the call session. The detail aggregate is computed in the application layer, so a future time-series store can replace the SQL adapter without changing the API or UI.

## Recording and transcription replacement points

`RecordingObjectStore` and `RecordingDownloadProvider` are infrastructure adapters. The current implementation uses an S3-compatible endpoint; an OSS adapter can implement the same ports without changing call services or Android code. Transcription is currently represented by `UnavailableTranscriptionRepository`; a provider-backed implementation can be bound later without changing the detail screen contract.
