package lol.mony.harmony.data

import lol.mony.harmony.data.model.DriveFile
import lol.mony.harmony.data.model.DriveFileAction
import lol.mony.harmony.data.model.DriveFileCard
import lol.mony.harmony.data.model.DriveFolder
import lol.mony.harmony.data.model.DriveListRow
import lol.mony.harmony.data.model.DrivePage
import lol.mony.harmony.data.model.DriveQuery
import lol.mony.harmony.data.model.DriveUsage
import lol.mony.harmony.data.model.DriveView
import lol.mony.harmony.data.model.HarmonyJson
import lol.mony.harmony.data.model.actions
import lol.mony.harmony.data.model.driveBreadcrumbs
import lol.mony.harmony.data.model.driveMoveTargets
import lol.mony.harmony.data.repo.driveDownloadName
import org.junit.Assert.*
import org.junit.Test

class DriveModelsTest {
    @Test fun `null encrypted filename is not replaced with a private storage key`() {
        val file = driveFile(name = null)
        assertEquals("Unnamed file", file.displayName)
        assertFalse(file.displayName.contains("u/"))
    }

    @Test fun `shared or signed out users cannot see owner mutations`() {
        val file = driveFile()
        val readOnly = setOf(DriveFileAction.Preview, DriveFileAction.Save)
        assertEquals(readOnly, file.actions("outsider", true))
        assertEquals(readOnly, file.actions(null, true))
    }

    @Test fun `owner file state drives actions and unknown states fail closed`() {
        assertTrue(driveFile().actions("owner", true).contains(DriveFileAction.Trash))
        val temporary = driveFile(state = "ephemeral").actions("owner", true)
        assertTrue(temporary.containsAll(setOf(DriveFileAction.Keep, DriveFileAction.DeleteForever)))
        assertFalse(temporary.contains(DriveFileAction.Trash))
        assertEquals(setOf(DriveFileAction.Restore, DriveFileAction.DeleteForever), driveFile(state = "trashed").actions("owner", true))
        assertTrue(driveFile(state = "gone").actions("owner", true).isEmpty())
        assertTrue(driveFile(state = "future-state").actions("owner", true).isEmpty())
    }

    @Test fun `a disabled public link feature still allows restricting an existing file address`() {
        val file = driveFile().copy(linkToken = "existing-file-address")
        assertTrue(file.actions("owner", false).contains(DriveFileAction.Link))
        assertFalse(file.copy(linkToken = null).actions("owner", false).contains(DriveFileAction.Link))
    }

    @Test fun `SVG is not rendered as an image preview`() {
        assertFalse(driveFile().copy(mimeType = "image/svg+xml").actions("owner", true).contains(DriveFileAction.Preview))
    }

    @Test fun `folder moves exclude descendants and handle cycles without hanging`() {
        val folders = listOf(folder("a", null), folder("b", "a"), folder("c", "b"), folder("d", null))
        assertEquals(listOf("d"), driveMoveTargets(folders, "a").map { it.id })
        val cyclic = listOf(folder("a", "b"), folder("b", "a"), folder("c", null))
        assertEquals(listOf("c"), driveMoveTargets(cyclic, "a").map { it.id })
    }

    @Test fun `breadcrumbs are root first and terminate on a cycle or missing parent`() {
        val folders = listOf(folder("a", null), folder("b", "a"), folder("c", "b"))
        assertEquals(listOf("a", "b", "c"), driveBreadcrumbs(folders, "c").map { it.id })
        assertEquals(2, driveBreadcrumbs(listOf(folder("a", "b"), folder("b", "a")), "a").size)
        assertTrue(driveBreadcrumbs(folders, "missing").isEmpty())
    }

    @Test fun `page uses offset and bigint counts`() {
        val row = DriveListRow(driveFile(), totalCount = 4_000_000_000)
        assertTrue(DrivePage(listOf(row), row.totalCount).hasMore)
        assertFalse(DrivePage(listOf(row), 51, offset = 50).hasMore)
    }

    @Test fun `denied and public link cards do not invent private object data`() {
        val denied = HarmonyJson.decodeFromString(DriveFileCard.serializer(), """{"access":false}""")
        assertFalse(denied.access)
        assertNull(denied.path)
        assertNull(denied.id)
        val link = HarmonyJson.decodeFromString(DriveFileCard.serializer(), """{"access":true,"via":"link","token":"public-file-token","name":"file","kind":"image","size_bytes":123}""")
        assertTrue(link.access)
        assertNull(link.path)
        assertFalse(link.own)
    }

    @Test fun `old usage answer defaults to enabled while switch false is preserved`() {
        val old = HarmonyJson.decodeFromString(DriveUsage.serializer(), usageJson)
        assertTrue(old.enabled)
        assertFalse(HarmonyJson.decodeFromString(DriveUsage.serializer(), usageJson.dropLast(1) + ",\"enabled\":false}").enabled)
    }

    @Test fun `invalid paging and context queries are rejected locally`() {
        assertThrows(IllegalArgumentException::class.java) { DriveQuery(limit = 0) }
        assertThrows(IllegalArgumentException::class.java) { DriveQuery(offset = -1) }
        assertThrows(IllegalArgumentException::class.java) { DriveQuery(view = DriveView.Channel) }
    }

    @Test fun `download names cannot be paths or control sequences`() {
        assertEquals("a_b_c_.txt", driveDownloadName("a/b\\c\n.txt"))
        assertEquals("download", driveDownloadName(" ... "))
        assertEquals(200, driveDownloadName("x".repeat(300)).length)
        assertEquals("a & # +.txt", driveDownloadName("a & # +.txt"))
    }
}

internal fun driveFile(name: String? = "photo.jpg", state: String = "drive") = DriveFile(
    id = "file", ownerId = "owner", bucket = "message_media", path = "u/owner/file.jpg", name = name,
    mimeType = "image/jpeg", kind = "image", sizeBytes = 123, state = state, createdAt = "2026-01-01T00:00:00Z", updatedAt = "2026-01-01T00:00:00Z",
)

private fun folder(id: String, parent: String?) = DriveFolder(id, "owner", parent, id, "", "")

internal val usageJson = """{"used_bytes":0,"quota_bytes":1048576,"ephemeral_bytes":0,"ephemeral_recent_bytes":0,"ephemeral_daily_bytes":1048576,"ephemeral_days":30,"trash_bytes":0,"file_count":0,"link_sharing":true}"""

internal val fileJson = """{"id":"file","owner_id":"owner","bucket":"message_media","path":"u/owner/file.jpg","name":"photo.jpg","mime_type":"image/jpeg","kind":"image","size_bytes":123,"state":"drive","created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:00:00Z"}"""
