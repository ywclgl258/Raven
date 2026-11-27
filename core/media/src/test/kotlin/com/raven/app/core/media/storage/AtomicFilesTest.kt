package com.raven.app.core.media.storage

import java.io.File
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AtomicFilesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `refused replacement keeps previous content and restores it when publish fails`() {
        val dir = tempFolder.newFolder("atomic-fail")
        val target = File(dir, "sidecar.json").apply { writeText("old-valid-content") }

        var attemptsAtTarget = 0
        // Android refuses to overwrite an existing file via renameTo; only the staged `.part`
        // source is blocked, so the backup move and the restore both succeed.
        val refusingStaged = { from: File, to: File ->
            if (to == target) {
                attemptsAtTarget += 1
                !from.name.endsWith(".part") && from.renameTo(to)
            } else {
                from.renameTo(to)
            }
        }

        val failure = assertThrows(IOException::class.java) {
            AtomicFiles.writeText(target, "new-content", refusingStaged)
        }

        assertEquals(3, attemptsAtTarget)
        assertTrue(failure.message!!.isNotBlank())
        assertEquals("old-valid-content", target.readText())
        // Nothing temporary is left behind next to the sidecar.
        assertEquals(listOf("sidecar.json"), dir.list().orEmpty().sorted())
    }

    @Test
    fun `successful replacement writes new content and leaves no backup`() {
        val dir = tempFolder.newFolder("atomic-ok")
        val target = File(dir, "queue.json").apply { writeText("old") }
        AtomicFiles.writeText(target, "new")
        assertEquals("new", target.readText())
        assertEquals(listOf("queue.json"), dir.list().orEmpty().sorted())
    }

    @Test
    fun `publish failure surfaces the surviving backup name instead of losing both copies`() {
        val dir = tempFolder.newFolder("atomic-unrecoverable")
        val target = File(dir, "book.json").apply { writeText("original") }

        // Everything after the backup move is refused, including the restore.
        var backupMoved = false
        val rename = { from: File, to: File ->
            if (to == target) {
                false
            } else {
                backupMoved = from.renameTo(to)
                backupMoved
            }
        }

        val failure = assertThrows(AtomicReplaceException::class.java) {
            AtomicFiles.writeText(target, "replacement", rename)
        }

        assertTrue(backupMoved)
        assertFalse(target.exists())
        assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains(".bak"))
        val backups = dir.listFiles().orEmpty().filter { it.name.endsWith(".bak") }
        assertEquals(1, backups.size)
        assertEquals("original", backups.single().readText())
    }

    @Test
    fun `publish moves a staged file into place atomically`() {
        val dir = tempFolder.newFolder("atomic-publish")
        val staged = File(dir, "page.image.part").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val target = File(dir, "page.image")
        AtomicFiles.publish(staged, target)
        assertArrayEquals(byteArrayOf(1, 2, 3), target.readBytes())
        assertFalse(staged.exists())
    }
}
