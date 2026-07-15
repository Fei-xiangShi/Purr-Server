#!/bin/sh
set -eu

require_strong_secret() {
  name="$1"
  value="$2"
  if [ "${#value}" -lt 16 ]; then
    echo "$name must contain at least 16 characters" >&2
    exit 1
  fi
  case "$value" in
    minioadmin|change-me*|dev-*)
      echo "$name must not use a development or placeholder value" >&2
      exit 1
      ;;
  esac
}

require_strong_secret MINIO_ROOT_PASSWORD "$MINIO_ROOT_PASSWORD"
require_strong_secret RECORDING_SECRET_KEY "$RECORDING_SECRET_KEY"
require_strong_secret AVATAR_SECRET_KEY "$AVATAR_SECRET_KEY"
[ -n "$MINIO_ROOT_USER" ] && [ -n "$RECORDING_ACCESS_KEY" ] && [ -n "$AVATAR_ACCESS_KEY" ] || {
  echo "MinIO root and service access keys must not be blank" >&2
  exit 1
}
[ "$RECORDING_ACCESS_KEY" != "$AVATAR_ACCESS_KEY" ] || {
  echo "recording and avatar access keys must be different" >&2
  exit 1
}
[ "$MINIO_ROOT_USER" != "$RECORDING_ACCESS_KEY" ] &&
  [ "$MINIO_ROOT_USER" != "$AVATAR_ACCESS_KEY" ] || {
  echo "root and service access keys must be different" >&2
  exit 1
}
[ "$RECORDING_SECRET_KEY" != "$AVATAR_SECRET_KEY" ] || {
  echo "recording and avatar secret keys must be different" >&2
  exit 1
}
[ "$MINIO_ROOT_PASSWORD" != "$RECORDING_SECRET_KEY" ] &&
  [ "$MINIO_ROOT_PASSWORD" != "$AVATAR_SECRET_KEY" ] || {
  echo "root and service-user secrets must be different" >&2
  exit 1
}

until mc alias set purr-minio http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"; do
  echo "waiting for minio..."
  sleep 1
done

mc mb --ignore-existing "purr-minio/$RECORDING_BUCKET"
mc mb --ignore-existing "purr-minio/$AVATAR_BUCKET"

mc admin policy create purr-minio purr-recording /config/recording-policy.json
mc admin policy create purr-minio purr-avatar /config/avatar-policy.json

# AddUser is an upsert in MinIO. Reapplying it makes Compose secret rotation
# effective instead of leaving an existing principal with its previous key.
mc admin user add purr-minio "$RECORDING_ACCESS_KEY" "$RECORDING_SECRET_KEY"
mc admin user add purr-minio "$AVATAR_ACCESS_KEY" "$AVATAR_SECRET_KEY"

mc admin policy attach purr-minio purr-recording --user "$RECORDING_ACCESS_KEY"
mc admin policy attach purr-minio purr-avatar --user "$AVATAR_ACCESS_KEY"
mc anonymous set download "purr-minio/$AVATAR_BUCKET"
# Preserve read access for avatar_url values created before avatars moved to a dedicated bucket.
mc anonymous set download "purr-minio/$RECORDING_BUCKET/avatars"
