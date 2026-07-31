# Recording Google Drive Archive

Completed LiveKit recordings remain in the local MinIO recording bucket and are streamed to Google Drive by a durable background worker. The LiveKit webhook only persists completion and wakes the worker; it never waits for the media upload.

## Google Drive Setup

1. Create a Google Cloud service account and enable the Google Drive API in its project.
2. Create or select the destination Drive folder.
3. Share that folder with the service-account email and grant Editor access.
4. Store the service-account JSON outside the repository.
5. Set `PURR_GOOGLE_DRIVE_SERVICE_ACCOUNT_FILE` to that host file and `PURR_GOOGLE_DRIVE_FOLDER_ID` to the folder ID in `.env`.

Compose mounts the credential read-only at `/run/secrets/purr-google-drive-service-account.json`. Credential content and access tokens must never be logged.

## Delivery Guarantees

- Upload work, attempts, availability, and leases are stored in PostgreSQL.
- Existing completed local recordings are included after the migration.
- Failed uploads retry indefinitely with bounded exponential backoff.
- Each Drive file carries `appProperties.purrRecordingId`. Before creating a file, the worker searches the configured folder for that property, preventing duplicates after an ambiguous timeout or database failure.
- A local object is never eligible for retention deletion until its Drive file ID and upload timestamp are committed.

## Retention

The retention worker runs daily at 17:00 in `Asia/Shanghai` by default. It deletes only local objects that:

- completed more than seven days ago;
- have a confirmed Google Drive file ID and upload timestamp; and
- have not already been deleted locally.

The database row and Drive identity remain after local deletion. Upload or deletion failures preserve the local object and are retried. Disable `PURR_GOOGLE_DRIVE_ENABLED` to stop uploads or `PURR_RECORDING_CLEANUP_ENABLED` to stop local deletion during an incident.
