package life.fxs.purr.server.recording

import java.net.URI
import life.fxs.purr.server.config.RecordingConfig
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest

fun interface RecordingObjectStore {
    fun delete(objectKey: String)
}

class S3RecordingObjectStore(
    private val config: RecordingConfig,
) : RecordingObjectStore, AutoCloseable {
    private val client = S3Client.builder()
        .endpointOverride(URI.create(config.endpoint))
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

    override fun delete(objectKey: String) {
        client.deleteObject(
            DeleteObjectRequest.builder()
                .bucket(config.bucket)
                .key(objectKey)
                .build(),
        )
    }

    override fun close() {
        client.close()
    }
}
