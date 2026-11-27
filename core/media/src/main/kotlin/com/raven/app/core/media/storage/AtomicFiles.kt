package com.raven.app.core.media.storage

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Failure raised when a file could not be published atomically. The message always names the
 * surviving backup so a manual rollback is possible when even the restore rename failed.
 */
internal class AtomicReplaceException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Crash-safe single-file replacement for sidecars, queue snapshots and downloaded pages.
 *
 * The old implementation deleted the target before retrying `renameTo`, so a refused second
 * rename left the destination missing and the previous content unrecoverable. Here the previous
 * file is only discarded after the new file has been published, and a failed publish restores it.
 */
internal object AtomicFiles {
    /**
     * Cursor variables are kept out of the inline lambda bodies below so Kotlin does not reject
     * captured `var` assignments from a crossinline block.
     */
    fun writeBytes(
        target: File,
        bytes: ByteArray,
        rename: (from: File, to: File) -> Boolean = { from, to -> from.renameTo(to) },
    ) {
        replace(target, rename) { temp ->
            FileOutputStream(temp).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
        }
    }

    fun writeText(
        target: File,
        text: String,
        rename: (from: File, to: File) -> Boolean = { from, to -> from.renameTo(to) },
    ) {
        writeBytes(target, text.toByteArray(Charsets.UTF_8), rename)
    }

    /** Publishes an already-written [staged] file, preserving the previous target until success. */
    fun publish(
        staged: File,
        target: File,
        rename: (from: File, to: File) -> Boolean = { from, to -> from.renameTo(to) },
    ) {
        check(staged.isFile) { "待发布文件不存在：${staged.path}" }
        renameSafely(staged, target, rename)
    }

    fun replace(
        target: File,
        rename: (from: File, to: File) -> Boolean = { from, to -> from.renameTo(to) },
        write: (temp: File) -> Unit,
    ) {
        val parent = target.parentFile ?: throw AtomicReplaceException("无效的文件路径：${target.path}")
        check(parent.isDirectory || parent.mkdirs()) { "无法创建目录：${parent.path}" }
        val temp = File(parent, "${target.name}.${System.nanoTime()}.part")
        try {
            write(temp)
            renameSafely(temp, target, rename)
        } finally {
            temp.delete()
        }
    }

    /**
     * First attempt is the cheap atomic replace (POSIX `rename` over an existing file). Only when
     * the platform refuses it is the previous file moved to a backup, and only a successful
     * publish removes that backup.
     */
    fun renameSafely(
        temp: File,
        target: File,
        rename: (from: File, to: File) -> Boolean = { from, to -> from.renameTo(to) },
    ) {
        val parent = target.parentFile ?: throw AtomicReplaceException("无效的文件路径：${target.path}")
        check(parent.isDirectory || parent.mkdirs()) { "无法创建目录：${parent.path}" }
        if (rename(temp, target)) return

        val backup = File(parent, "${target.name}.${System.nanoTime()}.bak")
        var movedOriginal = false
        try {
            if (target.exists()) {
                if (!rename(target, backup)) {
                    throw AtomicReplaceException("无法替换已有文件：${target.name}")
                }
                movedOriginal = true
            }
            if (!rename(temp, target)) {
                throw AtomicReplaceException("无法替换已有文件：${target.name}")
            }
            backup.delete()
            movedOriginal = false
        } catch (failure: Throwable) {
            if (movedOriginal) {
                // Never leave the destination empty: restore the untouched previous file. If even
                // this rename is refused, keep the backup on disk and surface its path.
                if (target.exists()) target.delete()
                if (!rename(backup, target)) {
                    throw AtomicReplaceException(
                        "无法恢复原有文件 ${target.name}，备份保留于 ${backup.name}",
                        failure,
                    )
                }
            }
            throw failure
        } finally {
            if (!movedOriginal) backup.delete()
        }
    }
}
