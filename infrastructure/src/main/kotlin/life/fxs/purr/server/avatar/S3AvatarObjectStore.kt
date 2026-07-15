package life.fxs.purr.server.avatar

import java.net.URI
import java.time.Duration
import java.util.UUID
import life.fxs.purr.server.application.port.AvatarObjectCatalog
import life.fxs.purr.server.application.port.AvatarObjectDeleter
import life.fxs.purr.server.application.port.AvatarObjectUploader
import life.fxs.purr.server.application.port.AvatarStorageReadiness
import life.fxs.purr.server.application.port.StoredAvatar
import life.fxs.purr.server.application.port.StoredAvatarObject
import life.fxs.purr.server.application.port.StoredAvatarPage
import life.fxs.purr.server.config.AvatarConfig
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.HeadBucketRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.PutObjectRequest

class S3AvatarObjectStore(
    private val config: AvatarConfig,
) : AvatarObjectUploader, AvatarObjectDeleter, AvatarObjectCatalog, AvatarStorageReadiness, AutoCloseable {
    private val client = S3Client.builder()
        .endpointOverride(URI.create(config.endpoint))
        .region(Region.of(config.region))
        .credentialsProvider(
            StaticCredentialsProvider.create(AwsBasicCredentials.create(config.accessKey, config.secretKey)),
        )
        .serviceConfiguration(
            S3Configuration.builder().pathStyleAccessEnabled(config.forcePathStyle).build(),
        )
        .overrideConfiguration(
            ClientOverrideConfiguration.builder()
                .apiCallAttemptTimeout(API_CALL_ATTEMPT_TIMEOUT)
                .apiCallTimeout(API_CALL_TIMEOUT)
                .build(),
        )
        .build()

    override fun put(userId: String, contentType: String, bytes: ByteArray): StoredAvatar {
        require(contentType == AVATAR_CONTENT_TYPE) { "Processed avatars must use $AVATAR_CONTENT_TYPE" }
        require(userId.matches(USER_ID_PATTERN)) { "Invalid avatar owner ID" }
        val key = "avatars/$userId/${UUID.randomUUID()}.jpg"
        client.putObject(
            PutObjectRequest.builder()
                .bucket(config.bucket)
                .key(key)
                .contentType(contentType)
                .cacheControl("public, max-age=31536000, immutable")
                .build(),
            RequestBody.fromBytes(bytes),
        )
        return StoredAvatar(key)
    }

    override fun delete(objectKey: String) {
        require(objectKey.matches(OBJECT_KEY_PATTERN)) { "Invalid avatar object key" }
        client.deleteObject(DeleteObjectRequest.builder().bucket(config.bucket).key(objectKey).build())
    }

    fun publicUrl(objectKey: String): String {
        require(objectKey.matches(OBJECT_KEY_PATTERN)) { "Invalid avatar object key" }
        return publicPrefix() + objectKey
    }

    override fun listObjects(continuationToken: String?, maxKeys: Int): StoredAvatarPage {
        require(maxKeys in 1..MAX_LIST_KEYS) { "Avatar list page size is invalid" }
        val response = client.listObjectsV2(
            ListObjectsV2Request.builder()
                .bucket(config.bucket)
                .prefix(AVATAR_PREFIX)
                .continuationToken(continuationToken)
                .maxKeys(maxKeys)
                .build(),
        )
        return StoredAvatarPage(
            objects = response.contents().map { item ->
                StoredAvatarObject(
                    objectKey = item.key(),
                    lastModifiedEpochMillis = item.lastModified().toEpochMilli(),
                )
            },
            nextContinuationToken = response.nextContinuationToken(),
        )
    }

    override fun isReady(): Boolean = try {
        client.headBucket(
            HeadBucketRequest.builder()
                .bucket(config.bucket)
                .overrideConfiguration { overrides ->
                    overrides.apiCallAttemptTimeout(READINESS_TIMEOUT)
                    overrides.apiCallTimeout(READINESS_TIMEOUT)
                }
                .build(),
        )
        true
    } catch (_: Exception) {
        false
    }

    override fun close() {
        client.close()
    }

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

    private companion object {
        const val AVATAR_CONTENT_TYPE = "image/jpeg"
        const val AVATAR_PREFIX = "avatars/"
        const val MAX_LIST_KEYS = 1_000
        val API_CALL_ATTEMPT_TIMEOUT: Duration = Duration.ofSeconds(5)
        val API_CALL_TIMEOUT: Duration = Duration.ofSeconds(15)
        val READINESS_TIMEOUT: Duration = Duration.ofSeconds(2)
        val USER_ID_PATTERN = Regex("[A-Za-z0-9._-]{1,64}")
        val OBJECT_KEY_PATTERN = Regex("avatars/[A-Za-z0-9._-]{1,64}/[0-9a-fA-F-]{36}\\.jpg")
    }
}
