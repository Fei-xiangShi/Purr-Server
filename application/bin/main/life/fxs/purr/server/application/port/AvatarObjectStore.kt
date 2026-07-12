package life.fxs.purr.server.application.port

data class StoredAvatar(
    val url: String,
)

interface AvatarObjectStore {
    fun put(userId: String, contentType: String, bytes: ByteArray): StoredAvatar

    fun deleteByUrl(url: String)
}
