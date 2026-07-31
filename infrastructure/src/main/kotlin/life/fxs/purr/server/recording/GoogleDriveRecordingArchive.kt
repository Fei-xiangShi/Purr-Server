package life.fxs.purr.server.recording

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.InputStreamContent
import com.google.api.client.json.gson.GsonFactory
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.GoogleCredentials
import java.io.FileInputStream
import life.fxs.purr.server.application.port.RecordingRecord
import life.fxs.purr.server.config.GoogleDriveConfig
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File

fun interface RecordingArchiveUploader {
    fun upload(recording: RecordingRecord, recordingObject: RecordingObject): String
}

class GoogleDriveRecordingArchive internal constructor(
    private val config: GoogleDriveConfig,
    private val gateway: GoogleDriveGateway,
) : RecordingArchiveUploader, AutoCloseable {
    constructor(
        config: GoogleDriveConfig,
        credentialsProvider: () -> GoogleCredentials = { loadDriveCredentials(config) },
    ) : this(config, GoogleApiDriveGateway(credentialsProvider()))

    override fun upload(recording: RecordingRecord, recordingObject: RecordingObject): String {
        gateway.find(config.folderId, recording.recordingId)?.let { return it }
        return gateway.create(
            folderId = config.folderId,
            recordingId = recording.recordingId,
            fileName = fileName(recording),
            recordingObject = recordingObject,
        )
    }

    private fun fileName(recording: RecordingRecord): String {
        val original = recording.objectKey
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
        return original ?: "${recording.recordingId}.ogg"
    }

    override fun close() = gateway.close()
}

internal interface GoogleDriveGateway : AutoCloseable {
    fun find(folderId: String, recordingId: String): String?

    fun create(
        folderId: String,
        recordingId: String,
        fileName: String,
        recordingObject: RecordingObject,
    ): String

    override fun close() = Unit
}

private class GoogleApiDriveGateway(
    credentials: GoogleCredentials,
) : GoogleDriveGateway {
    private val drive = Drive.Builder(
        GoogleNetHttpTransport.newTrustedTransport(),
        GsonFactory.getDefaultInstance(),
        HttpCredentialsAdapter(credentials),
    ).setApplicationName(APPLICATION_NAME).build()

    override fun find(folderId: String, recordingId: String): String? {
        val escapedRecordingId = recordingId.escapeDriveQueryValue()
        val escapedFolderId = folderId.escapeDriveQueryValue()
        return drive.files().list()
            .setQ(
                "'$escapedFolderId' in parents and trashed = false and " +
                    "appProperties has { key = '$RECORDING_PROPERTY' and value = '$escapedRecordingId' }",
            )
            .setSpaces("drive")
            .setPageSize(1)
            .setFields("files(id)")
            .setSupportsAllDrives(true)
            .setIncludeItemsFromAllDrives(true)
            .execute()
            .files
            ?.firstOrNull()
            ?.id
    }

    override fun create(
        folderId: String,
        recordingId: String,
        fileName: String,
        recordingObject: RecordingObject,
    ): String {
        val metadata = File()
            .setName(fileName)
            .setParents(listOf(folderId))
            .setAppProperties(mapOf(RECORDING_PROPERTY to recordingId))
        val media = InputStreamContent(
            recordingObject.contentType ?: DEFAULT_CONTENT_TYPE,
            recordingObject.input,
        ).apply {
            recordingObject.contentLength?.let(::setLength)
        }
        return drive.files().create(metadata, media)
            .setFields("id")
            .setSupportsAllDrives(true)
            .apply { mediaHttpUploader.isDirectUploadEnabled = false }
            .execute()
            .id
    }
}

private fun loadDriveCredentials(config: GoogleDriveConfig): GoogleCredentials =
    FileInputStream(config.serviceAccountPath).use { input ->
        GoogleCredentials.fromStream(input).createScoped(DRIVE_SCOPE)
    }

private fun String.escapeDriveQueryValue(): String = replace("\\", "\\\\").replace("'", "\\'")

private const val APPLICATION_NAME = "purr-server recording archive"
private const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive"
private const val RECORDING_PROPERTY = "purrRecordingId"
private const val DEFAULT_CONTENT_TYPE = "application/octet-stream"
