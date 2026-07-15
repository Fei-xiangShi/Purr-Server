# Avatar Storage Architecture

## Invariants

- Bootstrap data is insert-only. Application startup never overwrites a user's password, display name, or avatar.
- The database stores a stable object key and optimistic version. New uploads clear the legacy URL column; public URLs are derived from the current storage/CDN configuration.
- Untrusted uploads are admitted before their multipart body is read, decoded server-side, bounded by encoded size, dimensions, pixels, and processing concurrency, EXIF-oriented, then re-encoded as a 512px metadata-free JPEG.
- Profile replacement and cleanup enqueueing share one database transaction. Object deletion is asynchronous, leased, retryable, and idempotent.
- A periodic object/database reconciliation queues old unreferenced objects, covering failures between object upload and database commit.
- If a database commit returns an indeterminate result, the service reads the current reference before cleanup. It never blindly deletes an object that may have become authoritative.

## Update Flow

1. The API authenticates and rate-limits the caller, then enforces multipart part and byte limits.
2. `AvatarImageProcessor` decodes, validates, crops, and re-encodes the image.
3. `AvatarObjectStore` writes a unique immutable object under `avatars/{userId}/{uuid}.jpg`.
4. `UserProfileStore.compareAndSetAvatar` replaces the key only when the profile version still matches.
5. The same transaction enqueues either the superseded key or, after a conflict, the newly uploaded key.
6. `AvatarCleanupService` claims each object immediately before deletion, so a slow sequential batch cannot outlive a shared lease; `AvatarCleanupWorker` only owns scheduling and shutdown.
7. Failed deletion retries continue indefinitely. `cleanupMaxAttempts` is the threshold for switching to the configured maximum retry interval, not a terminal attempt count.

## Storage Isolation

Avatar and recording data use separate buckets, credentials, policies, and lifecycle settings. Production validation rejects shared access or secret keys. The API credential for the avatar bucket is limited to bucket inspection and `avatars/*` object operations. Root MinIO credentials are used only by the bootstrap container, which reapplies service users so rotated secrets take effect.

The bundled public storage virtual host proxies only `GET` and `HEAD` for avatar and recording object paths. Upload, delete, bucket, and administrative operations remain on the private container network.

Migration V17 leaves legacy absolute `avatar_url` values readable while all new uploads use `avatar_object_key` in the dedicated bucket. The bundled MinIO bootstrap retains download-only access to the old `purr-recordings/avatars` prefix; remove that legacy policy only after those rows and objects have been migrated or retired.

The bundled Compose deployment is a single-node reference topology. Internet-facing production deployments that require host or zone failure tolerance should point `PURR_AVATAR_*` at a replicated S3-compatible service or a distributed MinIO cluster. A Docker named volume is persistence, not a backup.

## Operations

Back up PostgreSQL and the avatar bucket as one recovery set. PostgreSQL needs point-in-time recovery; object storage needs cross-host or cross-region replication. Restore drills must verify that every non-null `avatar_object_key` is readable after URL resolution.

Alert on:

- `purr_avatar_uploads_total{outcome="failed"}` and `{outcome="conflict"}`
- `purr_avatar_cleanup_total{outcome="failed"}`
- `purr_avatar_cleanup_pending`
- `purr_avatar_cleanup_oldest_age_seconds`
- readiness failures for the avatar bucket
- sustained growth of pending `avatar_cleanup_tasks`

Changes to bucket names, policies, object-key formats, or image policy require a migration and a rollback plan. Do not mutate them only through environment variables after objects exist.
