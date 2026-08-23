package life.fxs.purr.server.recording

import java.net.URI
import java.time.Duration
import java.time.Instant
import life.fxs.purr.server.application.model.RecordingDownloadResult
import life.fxs.purr.server.config.RecordingConfig
import life.fxs.purr.server.application.port.RecordingDownloadProvider
import life.fxs.purr.server.application.port.RecordingRecord
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest

class S3RecordingDownloadUrlProvider(
    private val config: RecordingConfig,
    private val nowProvider: () -> Instant = Instant::now,
) : RecordingDownloadProvider, AutoCloseable {
    private val presigner = S3Presigner.builder()
        .endpointOverride(URI.create(config.publicEndpoint))
        .region(Region.of(config.region))
        .credentialsProvider(
            StaticCredentialsProvider.create(AwsBasicCredentials.create(config.accessKey, config.secretKey)),
        )
        .serviceConfiguration(
            S3Configuration.builder()
                .pathStyleAccessEnabled(config.forcePathStyle)
                .build(),
        )
        .build()

    override fun create(recording: RecordingRecord): RecordingDownloadResult {
        val recordingId = recording.recordingId
        val objectKey = recording.objectKey ?: error("Recording object key is required")
        val safeRecordingId = recordingId.replace(UNSAFE_FILENAME_CHARACTERS, "_")
        val getObjectRequest = GetObjectRequest.builder()
            .bucket(config.bucket)
            .key(objectKey)
            .responseContentType(RECORDING_CONTENT_TYPE)
            .responseContentDisposition("attachment; filename=\"recording-$safeRecordingId.ogg\"")
            .build()
        val presigned = presigner.presignGetObject(
            GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(config.downloadUrlTtlSeconds))
                .getObjectRequest(getObjectRequest)
                .build(),
        )
        return RecordingDownloadResult(
            recordingId = recordingId,
            url = presigned.url().toString(),
            expiresAtEpochMillis = nowProvider().plusSeconds(config.downloadUrlTtlSeconds).toEpochMilli(),
        )
    }

    override fun close() {
        presigner.close()
    }

    private companion object {
        val UNSAFE_FILENAME_CHARACTERS = Regex("[^A-Za-z0-9._-]")
        const val RECORDING_CONTENT_TYPE = "audio/ogg"
    }
}
