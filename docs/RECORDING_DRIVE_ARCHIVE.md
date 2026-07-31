# Recording Google Drive Archive

Completed LiveKit recordings remain in the local MinIO recording bucket and are streamed to Google Drive by a durable background worker. The LiveKit webhook only persists completion and wakes the worker; it never waits for the media upload.

## Google Drive Setup

The archive uses OAuth 2.0 as the Google user, so uploads consume that user's personal Google One storage. A Google Workspace subscription or Shared Drive is not required.

1. Open [Google Cloud Console](https://console.cloud.google.com/), create or select a project, and enable **Google Drive API**.
2. In **Google Auth Platform**, configure the app for an external audience, add the Google account as a test user, and add the `https://www.googleapis.com/auth/drive` scope.
3. Create an OAuth client with application type **Desktop app**, then download its client JSON.
4. Create or select a folder in **My Drive**. Copy the ID after `/drive/folders/` from the browser URL.
5. Generate the server credential locally from the repository root:

   ```bash
   mkdir -p secrets
   ./gradlew -Dorg.gradle.java.home=/usr/lib/jvm/zulu-17 \
     :infrastructure:authorizeGoogleDrive \
     --args="/path/to/client_secret.json secrets/google-drive-oauth.json"
   ```

   The command prints an official Google authorization URL and attempts to open it. Sign in with the account that owns the storage, approve access, and wait for the terminal to confirm that the credential was written.

6. Set the OAuth app publishing status to **Production** before relying on unattended uploads. Google normally expires refresh tokens after seven days while an external app remains in Testing. A private, unverified app may show a warning; authorize only the account that owns this deployment and do not distribute the client.
7. Configure `.env`:

   ```dotenv
   PURR_GOOGLE_DRIVE_OAUTH_CREDENTIAL_FILE=./secrets/google-drive-oauth.json
   PURR_GOOGLE_DRIVE_FOLDER_ID=replace-with-google-drive-folder-id
   ```

Compose mounts the generated credential read-only at `/run/secrets/purr-google-drive-oauth.json`. The downloaded client JSON and generated authorized-user JSON must stay outside version control. Credential content, authorization codes, refresh tokens, and access tokens must never be logged.

To recover from a revoked or expired grant, rerun `:infrastructure:authorizeGoogleDrive`, replace the generated credential file, and restart `purr-server`. Upload failures retain the local recording and continue retrying; they do not make it eligible for deletion.

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
