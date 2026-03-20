#!/bin/sh
set -eu

until mc alias set purr-minio http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"; do
  echo "waiting for minio..."
  sleep 1
done

mc mb --ignore-existing "purr-minio/$MINIO_BUCKET"
