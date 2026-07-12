package life.fxs.purr.server.avatar

import java.net.URI
import java.util.UUID
import life.fxs.purr.server.application.port.AvatarObjectStore
import life.fxs.purr.server.application.port.StoredAvatar
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest

class S3AvatarObjectStore(
    private val config: AvatarStorageConfig,
) : AvatarObjectStore, AutoCloseable {
    private val client = S3Client.builder()
        .endpointOverride(URI.create(config.endpoint))
        .region(Region.of(config.region))
        .credentialsProvider(
            StaticCredentialsProvider.create(AwsBasicCredentials.create(config.accessKey, config.secretKey)),
        )
        .serviceConfiguration(
            S3Configuration.builder().pathStyleAccessEnabled(config.forcePathStyle).build(),
        )
        .build()

    override fun put(userId: String, contentType: String, bytes: ByteArray): StoredAvatar {
        val extension = when (contentType) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> error("Unsupported avatar content type: $contentType")
        }
        val key = "avatars/$userId/${UUID.randomUUID()}.$extension"
        client.putObject(
            PutObjectRequest.builder()
                .bucket(config.bucket)
                .key(key)
                .contentType(contentType)
                .cacheControl("public, max-age=86400")
                .build(),
            RequestBody.fromBytes(bytes),
        )
        return StoredAvatar(publicUrl(key))
    }

    override fun deleteByUrl(url: String) {
        val prefix = publicPrefix()
        val key = url.removePrefix(prefix).takeIf { url.startsWith(prefix) } ?: return
        client.deleteObject(DeleteObjectRequest.builder().bucket(config.bucket).key(key).build())
    }

    override fun close() {
        client.close()
    }

    private fun publicUrl(key: String): String = publicPrefix() + key

    private fun publicPrefix(): String {
        val endpoint = URI.create(config.publicEndpoint.trimEnd('/'))
        return if (config.forcePathStyle) {
            "${endpoint}/".trimEnd('/') + "/${config.bucket}/"
        } else {
            URI(
                endpoint.scheme,
                endpoint.userInfo,
                "${config.bucket}.${endpoint.host}",
                endpoint.port,
                "/",
                null,
                null,
            ).toString()
        }
    }
}

data class AvatarStorageConfig(
    val bucket: String,
    val endpoint: String,
    val publicEndpoint: String,
    val accessKey: String,
    val secretKey: String,
    val region: String,
    val forcePathStyle: Boolean,
)
