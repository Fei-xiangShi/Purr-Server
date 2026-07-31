package life.fxs.purr.server.recording

import com.google.auth.oauth2.GoogleCredentials
import com.google.api.services.drive.model.File

internal class GoogleDriveFolderProvisioner(
    private val gateway: DriveFolderGateway,
) {
    fun ensureFolder(folderName: String): String {
        require(folderName.isNotBlank() && folderName.length <= MAX_FOLDER_NAME_LENGTH) {
            "Google Drive archive folder name must contain between 1 and $MAX_FOLDER_NAME_LENGTH characters"
        }
        return gateway.findArchiveFolder() ?: gateway.createArchiveFolder(folderName)
    }
}

internal interface DriveFolderGateway {
    fun findArchiveFolder(): String?

    fun createArchiveFolder(folderName: String): String
}

internal class GoogleApiDriveFolderGateway(
    credentials: GoogleCredentials,
) : DriveFolderGateway {
    private val drive = GoogleDriveClientFactory.create(credentials)

    override fun findArchiveFolder(): String? = drive.files().list()
        .setQ(
            "trashed = false and mimeType = '$FOLDER_MIME_TYPE' and " +
                "appProperties has { key = '$ARCHIVE_FOLDER_PROPERTY' and value = '$PROPERTY_VALUE' }",
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

    override fun createArchiveFolder(folderName: String): String {
        val metadata = File()
            .setName(folderName)
            .setMimeType(FOLDER_MIME_TYPE)
            .setAppProperties(mapOf(ARCHIVE_FOLDER_PROPERTY to PROPERTY_VALUE))
        return drive.files().create(metadata)
            .setFields("id")
            .setSupportsAllDrives(true)
            .execute()
            .id
    }
}

private const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
private const val ARCHIVE_FOLDER_PROPERTY = "purrRecordingArchiveFolder"
private const val PROPERTY_VALUE = "true"
private const val MAX_FOLDER_NAME_LENGTH = 255
