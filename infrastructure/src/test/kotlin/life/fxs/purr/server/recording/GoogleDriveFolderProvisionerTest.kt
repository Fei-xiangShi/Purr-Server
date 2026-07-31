package life.fxs.purr.server.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GoogleDriveFolderProvisionerTest {
    @Test
    fun `existing application folder is reused`() {
        val gateway = FakeDriveFolderGateway(existingFolderId = "existing-folder-id")

        val folderId = GoogleDriveFolderProvisioner(gateway).ensureFolder("Purr Recordings")

        assertEquals("existing-folder-id", folderId)
        assertEquals(1, gateway.findCalls)
        assertEquals(0, gateway.createCalls)
    }

    @Test
    fun `missing application folder is created with configured name`() {
        val gateway = FakeDriveFolderGateway(existingFolderId = null)

        val folderId = GoogleDriveFolderProvisioner(gateway).ensureFolder("Private Recordings")

        assertEquals("created-folder-id", folderId)
        assertEquals(1, gateway.findCalls)
        assertEquals(1, gateway.createCalls)
        assertEquals("Private Recordings", gateway.createdFolderName)
    }

    @Test
    fun `blank folder name is rejected before Drive access`() {
        val gateway = FakeDriveFolderGateway(existingFolderId = null)

        assertFailsWith<IllegalArgumentException> {
            GoogleDriveFolderProvisioner(gateway).ensureFolder(" ")
        }

        assertEquals(0, gateway.findCalls)
        assertEquals(0, gateway.createCalls)
    }

    private class FakeDriveFolderGateway(
        private val existingFolderId: String?,
    ) : DriveFolderGateway {
        var findCalls = 0
        var createCalls = 0
        var createdFolderName: String? = null

        override fun findArchiveFolder(): String? {
            findCalls++
            return existingFolderId
        }

        override fun createArchiveFolder(folderName: String): String {
            createCalls++
            createdFolderName = folderName
            return "created-folder-id"
        }
    }
}
