package life.fxs.purr.server.recording

import java.io.InputStream
import java.net.URI
import life.fxs.purr.server.config.RecordingConfig
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest

fun interface RecordingObjectStore {
    fun delete(objectKey: String)
}

fun interface RecordingObjectReader {
    fun open(objectKey: String): RecordingObject
}

class RecordingObject(
    val input: InputStream,
    val contentLength: Long?,
    val contentType: String?,
) : AutoCloseable {
    override fun close() = input.close()
}

class S3RecordingObjectStore(
    private val config: RecordingConfig,
) : RecordingObjectStore, RecordingObjectReader, AutoCloseable {
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

    override fun open(objectKey: String): RecordingObject {
        val response = client.getObject(
            GetObjectRequest.builder()
                .bucket(config.bucket)
                .key(objectKey)
                .build(),
        )
        return RecordingObject(
            input = response,
            contentLength = response.response().contentLength(),
            contentType = response.response().contentType(),
        )
    }

    override fun close() {
        client.close()
    }
}
