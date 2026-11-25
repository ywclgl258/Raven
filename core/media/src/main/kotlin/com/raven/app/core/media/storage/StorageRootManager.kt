package com.raven.app.core.media.storage

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.raven.app.core.common.AppDispatchers
import com.raven.app.core.common.DefaultAppDispatchers
import com.raven.app.core.database.datastore.SettingsDataStore
import com.raven.app.core.model.DownloadKind
import com.raven.app.core.model.DownloadStatus
import com.raven.app.core.model.SiteId
import com.raven.app.core.model.StorageLocation
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json

/**
 * Concrete filesystem layout for a [StorageLocation].
 */
data class StorageLayout(
    val location: StorageLocation,
    val root: File,
) {
    val videosDir: File get() = if (location == StorageLocation.PRIVATE) root else File(root, "videos")
    val videoCacheDir: File get() = if (location == StorageLocation.PRIVATE) File(root, "downloads") else File(videosDir, "cache")
    val videoMetaDir: File get() = if (location == StorageLocation.PRIVATE) File(root, "download-meta") else File(videosDir, "meta")
    val comicsDir: File get() = if (location == StorageLocation.PRIVATE) File(root, "gallery-downloads") else File(root, "comics")
    val localLibraryDir: File get() = File(root, "local-library")
    val manifestFile: File get() = File(root, "library.json")
}

/**
 * Current storage root status surfaced to Settings and Migration screens.
 */
data class StorageStatus(
    val activeLocation: StorageLocation,
    val configuredLocation: StorageLocation,
    val pendingLocation: StorageLocation?,
    val sharedPermissionGranted: Boolean,
    val sharedWritable: Boolean,
    val activePath: String,
    val sharedPath: String?,
    val note: String = "",
)

/**
 * Result of cold-start pending storage application and reinstall adoption.
 */
data class StorageStartupResult(
    val activeLocation: StorageLocation,
    val appliedPending: Boolean = false,
    val adoptedSharedLibrary: Boolean = false,
    val notice: String? = null,
)

/**
 * Outcome of SHA-256 verified migration from PRIVATE to SHARED storage.
 */
data class StorageMigrationResult(
    val copiedFiles: Int,
    val skippedFiles: Int,
    val totalBytes: Long,
    val pendingLocation: StorageLocation,
    val summary: String,
)

/**
 * Portable disk sidecar surviving uninstall/reinstall so downloads recover without Room/Media3 DBs.
 */
@Serializable
data class DiskSidecarRecord(
    val schema: Int = SCHEMA_VERSION,
    val kind: DownloadKind,
    val siteId: SiteId,
    val mediaId: String,
    val quality: String = "",
    val cacheKey: String = "",
    val title: String = "",
    val url: String = "",
    val coverUrl: String = "",
    val durationSeconds: Int? = null,
    val totalPages: Int = 0,
    val completedPages: Int = 0,
    val bytes: Long = 0L,
    val updatedAt: Long = 0L,
    val status: DownloadStatus = DownloadStatus.COMPLETED,
    val detail: String = "",
    val versionCode: Int = 0,
    @Transient val sourceRoot: String = "",
    @Transient val intactSelection: List<Int> = emptyList(),
) {
    val compositeDownloadId: String
        get() = if (kind == DownloadKind.VIDEO) {
            "${siteId.value}:$mediaId:$quality"
        } else {
            "${siteId.value}:$mediaId"
        }

    companion object {
        const val SCHEMA_VERSION: Int = 1
    }
}

/**
 * Manages dual download roots (`PRIVATE` app sandbox `filesDir` and `SHARED` `/storage/emulated/0/Raven`
 * with `.nomedia`), cold-start `pending_storage_location` deferred root swap, reinstall sidecar recovery,
 * and SHA-256 verified migration.
 */
class StorageRootManager(
    private val context: Context?,
    private val settingsDataStore: SettingsDataStore?,
    private val privateRootOverride: File? = null,
    private val sharedRootOverride: File? = null,
    private val sharedPermissionProvider: (() -> Boolean)? = null,
    private val dispatchers: AppDispatchers = DefaultAppDispatchers(),
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    constructor(
        context: Context,
        settingsDataStore: SettingsDataStore,
        dispatchers: AppDispatchers = DefaultAppDispatchers(),
    ) : this(
        context = context.applicationContext,
        settingsDataStore = settingsDataStore,
        privateRootOverride = null,
        sharedRootOverride = null,
        sharedPermissionProvider = null,
        dispatchers = dispatchers,
    )

    private val lock = Any()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Suppress("DEPRECATION")
    private val externalVolume: File? = runCatching {
        if (sharedRootOverride != null) null else Environment.getExternalStorageDirectory()
    }.getOrNull()

    val privateRoot: File = privateRootOverride
        ?: checkNotNull(context?.applicationContext?.filesDir) { "Context or privateRootOverride required" }

    val sharedRoot: File? = sharedRootOverride ?: externalVolume?.let { File(it, SHARED_FOLDER_NAME) }

    @Volatile
    private var boundLocation: StorageLocation = StorageLocation.PRIVATE

    @Volatile
    private var configuredLocation: StorageLocation = StorageLocation.PRIVATE

    @Volatile
    private var pendingLocation: StorageLocation? = null

    @Volatile
    private var startupNotice: String? = null

    @Volatile
    var isMigrating: Boolean = false
        private set

    private val _sidecarRevision = MutableStateFlow(0L)
    val sidecarRevision: StateFlow<Long> = _sidecarRevision.asStateFlow()

    private val knownSharedComics = LinkedHashSet<String>()

    fun isSharedSupported(): Boolean = sharedRoot != null

    val canWriteDownloads: Boolean
        get() = !isMigrating && pendingLocation == null

    fun checkDownloadsAllowed() {
        check(!isMigrating) { "下载目录正在迁移中，请稍候" }
        check(pendingLocation == null) { "下载位置已准备切换，请重启应用后继续下载" }
    }

    @Synchronized
    fun beginMigration() {
        check(!isMigrating && pendingLocation == null) { "下载位置正在切换，请重启应用后再操作" }
        isMigrating = true
    }

    @Synchronized
    fun endMigration() {
        isMigrating = false
    }

    fun isSharedPermissionGranted(): Boolean = when {
        sharedRoot == null -> false
        sharedPermissionProvider != null -> sharedPermissionProvider.invoke()
        sharedRootOverride != null -> true
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> runCatching {
            Environment.isExternalStorageManager()
        }.getOrDefault(false)
        context != null -> ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
        else -> false
    }

    /**
     * Must be called once during cold start BEFORE opening any Media3 [androidx.media3.datasource.cache.SimpleCache].
     * Applies any deferred `pending_storage_location` and auto-adopts an existing `/storage/emulated/0/Raven`
     * library on fresh reinstall when private storage is empty.
     */
    suspend fun applyPendingOnColdStart(): StorageStartupResult = withContext(dispatchers.io) {
        val currentSettings = settingsDataStore?.currentSettings()
        val storedLocation = currentSettings?.storageLocation ?: configuredLocation
        val storedPending = currentSettings?.pendingStorageLocation ?: pendingLocation

        if (storedPending != null) {
            val targetWritable = storedPending == StorageLocation.PRIVATE || isSharedWritable(createDirectories = true)
            val resolved = if (targetWritable) storedPending else StorageLocation.PRIVATE
            settingsDataStore?.setStorageLocation(resolved)
            settingsDataStore?.setPendingStorageLocation(null)
            synchronized(lock) {
                boundLocation = resolved
                configuredLocation = resolved
                pendingLocation = null
                startupNotice = if (targetWritable) null else SWITCH_FAILED_NOTICE
            }
            return@withContext StorageStartupResult(
                activeLocation = resolved,
                appliedPending = targetWritable,
                notice = startupNotice,
            )
        }

        // Reinstall adoption: if configured is PRIVATE, private has no downloads, and SHARED has real sidecars.
        var adopted = false
        var effective = storedLocation
        if (storedLocation == StorageLocation.PRIVATE &&
            !hasPrivateLibraryContent() &&
            hasSharedLibraryContent()
        ) {
            effective = StorageLocation.SHARED
            adopted = true
            settingsDataStore?.setStorageLocation(StorageLocation.SHARED)
        } else if (storedLocation == StorageLocation.SHARED && !isSharedWritable(createDirectories = true)) {
            effective = StorageLocation.PRIVATE
        }

        synchronized(lock) {
            boundLocation = effective
            configuredLocation = if (adopted) StorageLocation.SHARED else storedLocation
            pendingLocation = null
        }
        StorageStartupResult(
            activeLocation = effective,
            adoptedSharedLibrary = adopted,
        )
    }

    fun activeLocation(): StorageLocation = boundLocation

    fun layout(): StorageLayout = layoutOf(boundLocation)

    fun layoutOf(location: StorageLocation): StorageLayout = when (location) {
        StorageLocation.PRIVATE -> StorageLayout(StorageLocation.PRIVATE, privateRoot)
        StorageLocation.SHARED -> StorageLayout(StorageLocation.SHARED, sharedRoot ?: privateRoot)
    }

    fun readableLayouts(): List<StorageLayout> = buildList {
        if (isSharedPermissionGranted() && sharedRoot != null) {
            add(layoutOf(StorageLocation.SHARED))
        }
        add(layoutOf(StorageLocation.PRIVATE))
    }.distinctBy { it.root.canonicalPath }

    fun resolveSourceLayout(sourceRootPath: String): StorageLayout {
        if (sourceRootPath.isBlank()) return layout()
        val targetCanonical = File(sourceRootPath).canonicalPath
        return readableLayouts().firstOrNull { it.root.canonicalPath == targetCanonical }
            ?: error("下载来源目录不可用，请检查存储权限")
    }

    fun isSharedSource(path: String): Boolean = path.isNotBlank() && sharedRoot?.let {
        runCatching { it.canonicalFile == File(path).canonicalFile }.getOrDefault(false)
    } == true

    fun ensure(location: StorageLocation = activeLocation()): StorageLayout {
        val target = layoutOf(location)
        val dirs = if (location == StorageLocation.SHARED) {
            listOf(target.root, target.videosDir, target.videoCacheDir, target.videoMetaDir, target.comicsDir)
        } else {
            listOf(target.videoCacheDir, target.videoMetaDir, target.comicsDir, target.localLibraryDir)
        }
        dirs.forEach { dir ->
            check(dir.isDirectory || dir.mkdirs()) { "无法创建下载目录：${dir.path}" }
        }
        if (location == StorageLocation.SHARED) {
            val nomedia = File(target.root, ".nomedia")
            if (!nomedia.isFile) {
                runCatching { nomedia.writeText("") }
            }
        }
        return target
    }

    fun isSharedWritable(createDirectories: Boolean = true): Boolean = runCatching {
        if (!isSharedPermissionGranted() || sharedRoot == null) return false
        val target = if (createDirectories) ensure(StorageLocation.SHARED) else layoutOf(StorageLocation.SHARED)
        for (dir in listOf(target.root, target.videoCacheDir, target.videoMetaDir, target.comicsDir)) {
            if (!dir.isDirectory) return false
            val probe = File.createTempFile(".raven-write-probe-", null, dir)
            try {
                probe.writeText("1")
            } finally {
                probe.delete()
            }
        }
        true
    }.getOrDefault(false)

    fun status(): StorageStatus {
        val granted = isSharedPermissionGranted()
        val writable = granted && isSharedWritable(createDirectories = false)
        val active = activeLocation()
        val configured = configuredLocation
        val pending = pendingLocation
        val note = when {
            startupNotice != null -> startupNotice.orEmpty()
            sharedRoot == null -> "未检测到可写的外部存储卷"
            pending != null -> "已准备好切换到${if (pending == StorageLocation.SHARED) "公共目录" else "应用内存储"}，重启应用后生效"
            configured == StorageLocation.SHARED && !granted -> "所有文件访问权限已被撤销，请重新授权或切回应用内存储"
            configured == StorageLocation.SHARED && !writable -> "公共目录不可写，已暂存至应用内存储"
            else -> ""
        }
        return StorageStatus(
            activeLocation = active,
            configuredLocation = configured,
            pendingLocation = pending,
            sharedPermissionGranted = granted,
            sharedWritable = writable,
            activePath = layout().root.path,
            sharedPath = sharedRoot?.path,
            note = note,
        )
    }

    suspend fun statusAsync(): StorageStatus = withContext(dispatchers.io) { status() }

    fun permissionIntent(): Intent? {
        val appContext = context?.applicationContext ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val packageUri = Uri.parse("package:${appContext.packageName}")
        val appSpecific = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri)
        return appSpecific.takeIf { it.resolveActivity(appContext.packageManager) != null }
            ?: Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
    }

    suspend fun requestDeferredSwitch(target: StorageLocation) {
        if (target == StorageLocation.SHARED) {
            check(isSharedPermissionGranted()) { "请先授予公共目录存储权限" }
        }
        settingsDataStore?.setPendingStorageLocation(target)
        synchronized(lock) {
            pendingLocation = target
        }
    }

    fun noteSharedGallery(relativeFolder: String) {
        if (relativeFolder.isBlank()) return
        synchronized(knownSharedComics) {
            knownSharedComics.add(relativeFolder)
        }
    }

    fun forgetSharedGallery(relativeFolder: String) {
        if (relativeFolder.isBlank()) return
        synchronized(knownSharedComics) {
            knownSharedComics.remove(relativeFolder)
        }
    }

    fun galleryRemovalLayouts(
        sourceRoot: String = "",
        knownRoot: String = "",
        relativeFolder: String = "",
    ): List<StorageLayout> {
        val trackedShared = synchronized(knownSharedComics) { relativeFolder in knownSharedComics }
        val hasSharedCopy = isSharedSource(sourceRoot) || (
            sourceRoot.isBlank() && (
                isSharedSource(knownRoot) || (
                    relativeFolder.isNotBlank() && (
                        trackedShared ||
                            sharedRoot?.let { File(layoutOf(StorageLocation.SHARED).comicsDir, relativeFolder).exists() } == true
                        )
                    )
                )
            )
        check(!hasSharedCopy || isSharedPermissionGranted()) { "公共下载目录权限不可用，请重新授权后重试删除" }
        return if (sourceRoot.isBlank()) readableLayouts() else listOf(resolveSourceLayout(sourceRoot))
    }

    // ---- Disk Sidecar Index (survives reinstall) ----

    fun videoSidecarFile(siteId: SiteId, mediaId: String, quality: String, layout: StorageLayout = ensure()): File =
        File(layout.videoMetaDir, "${safeFileName("${siteId.value}|$mediaId|$quality")}.json")

    fun comicSidecarFile(siteId: SiteId, mediaId: String, layout: StorageLayout = ensure()): File =
        File(layout.comicsDir, "${safeFileName("${siteId.value}|$mediaId")}.json")

    fun writeVideoSidecar(record: DiskSidecarRecord, layout: StorageLayout? = null) {
        val target = layout ?: ensure()
        val normalized = record.copy(
            kind = DownloadKind.VIDEO,
            cacheKey = record.cacheKey.ifBlank { "${record.siteId.value}:${record.mediaId}:${record.quality}" },
            updatedAt = record.updatedAt.takeIf { it > 0L } ?: clockMs(),
            versionCode = record.versionCode.takeIf { it > 0 } ?: currentVersionCode(),
        )
        atomicWriteText(
            videoSidecarFile(normalized.siteId, normalized.mediaId, normalized.quality, target),
            json.encodeToString(DiskSidecarRecord.serializer(), normalized),
        )
        _sidecarRevision.update { it + 1 }
    }

    fun removeVideoSidecar(siteId: SiteId, mediaId: String, quality: String) {
        readableLayouts().forEach { layout ->
            videoSidecarFile(siteId, mediaId, quality, layout).delete()
        }
        _sidecarRevision.update { it + 1 }
    }

    fun writeComicSidecar(record: DiskSidecarRecord, layout: StorageLayout? = null) {
        val target = layout ?: ensure()
        val normalized = record.copy(
            updatedAt = record.updatedAt.takeIf { it > 0L } ?: clockMs(),
            versionCode = record.versionCode.takeIf { it > 0 } ?: currentVersionCode(),
        )
        if (target.location == StorageLocation.SHARED) {
            noteSharedGallery("${normalized.siteId.value}/${sha256Hex(normalized.mediaId)}")
        }
        atomicWriteText(
            comicSidecarFile(normalized.siteId, normalized.mediaId, target),
            json.encodeToString(DiskSidecarRecord.serializer(), normalized),
        )
        _sidecarRevision.update { it + 1 }
    }

    fun removeComicSidecar(siteId: SiteId, mediaId: String, sourceRoot: String = "") {
        val targets = if (sourceRoot.isBlank()) {
            readableLayouts().map { comicSidecarFile(siteId, mediaId, it) }
        } else {
            listOf(comicSidecarFile(siteId, mediaId, resolveSourceLayout(sourceRoot)))
        }
        targets.forEach { file ->
            if (file.exists() && !file.delete()) {
                throw IOException("无法删除图集索引：${file.name}")
            }
        }
        _sidecarRevision.update { it + 1 }
    }

    fun readSidecar(file: File): DiskSidecarRecord? = runCatching {
        if (!file.isFile || file.length() !in 1L..MAX_SIDECAR_BYTES) return null
        json.decodeFromString(DiskSidecarRecord.serializer(), file.readText())
    }.getOrNull()

    /**
     * Scans all readable storage roots for video sidecars and gallery book folders, validating
     * actual media presence via [isVideoCached] and [inspectComicFolder].
     */
    suspend fun scanDiskSidecars(
        isVideoCached: (cacheKey: String, sourceRoot: String) -> Boolean,
        inspectComicFolder: (siteId: SiteId, folder: File, sourceRoot: String) -> DiskSidecarRecord? = { _, _, _ -> null },
    ): List<DiskSidecarRecord> = withContext(dispatchers.io) {
        val found = LinkedHashMap<String, DiskSidecarRecord>()
        for (layout in readableLayouts()) {
            currentCoroutineContext().ensureActive()
            val rootPath = layout.root.absolutePath

            // 1. Video sidecars
            layout.videoMetaDir.listFiles().orEmpty()
                .filter { it.isFile && it.name.endsWith(".json") }
                .forEach { file ->
                    val raw = readSidecar(file) ?: return@forEach
                    if (raw.kind != DownloadKind.VIDEO || raw.mediaId.isBlank() || raw.quality.isBlank()) return@forEach
                    val key = raw.cacheKey.ifBlank { "${raw.siteId.value}:${raw.mediaId}:${raw.quality}" }
                    val cached = runCatching { isVideoCached(key, rootPath) }.getOrDefault(false)
                    val verified = raw.copy(
                        cacheKey = key,
                        status = if (cached) DownloadStatus.COMPLETED else DownloadStatus.FAILED,
                        sourceRoot = rootPath,
                    )
                    if (found[verified.compositeDownloadId]?.status != DownloadStatus.COMPLETED) {
                        found[verified.compositeDownloadId] = verified
                    }
                }

            // 2. Comic/photo book folders
            if (!directChild(layout.root, layout.comicsDir)) continue
            for (siteDir in layout.comicsDir.listFiles().orEmpty()) {
                currentCoroutineContext().ensureActive()
                if (!siteDir.isDirectory || !directChild(layout.comicsDir, siteDir)) continue
                val siteId = runCatching { SiteId.of(siteDir.name) }.getOrNull()?.takeIf { it.isKnown } ?: continue
                for (bookDir in siteDir.listFiles().orEmpty()) {
                    currentCoroutineContext().ensureActive()
                    if (!bookDir.isDirectory || !directChild(siteDir, bookDir)) continue
                    val record = inspectComicFolder(siteId, bookDir, rootPath) ?: continue
                    if (layout.location == StorageLocation.SHARED) {
                        noteSharedGallery("${siteId.value}/${bookDir.name}")
                    }
                    if (found[record.compositeDownloadId]?.status != DownloadStatus.COMPLETED) {
                        found[record.compositeDownloadId] = record
                    }
                }
            }
        }
        found.values.sortedByDescending { it.updatedAt }
    }

    // ---- SHA-256 Verified Migration (PRIVATE -> SHARED) ----

    /**
     * Copies downloaded videos and gallery books from `PRIVATE` to `SHARED` with full SHA-256
     * verification and deferred `pending_storage_location = SHARED` commit.
     *
     * Never deletes original `PRIVATE` files so rollback remains intact until the user clears them.
     */
    suspend fun migratePrivateToShared(
        copyVideos: suspend () -> Unit,
        isCompleteComicBook: (File) -> Boolean = { folder -> File(folder, "complete").isFile && File(folder, "book.json").isFile },
        onProgress: (doneBytes: Long, totalBytes: Long, currentFileName: String) -> Unit = { _, _, _ -> },
    ): StorageMigrationResult = withContext(dispatchers.io) {
        check(isSharedPermissionGranted()) { "请先授予公共目录存储权限" }
        val source = layoutOf(StorageLocation.PRIVATE)
        val destination = layoutOf(StorageLocation.SHARED)
        val coroutineCtx = currentCoroutineContext()

        fun ensureConfined(root: File, file: File) {
            val base = root.canonicalPath
            val target = file.canonicalPath
            check(target == base || target.startsWith(base + File.separator)) {
                "迁移路径超出允许范围：${file.path}"
            }
        }

        check(source.root.canonicalPath != destination.root.canonicalPath) { "来源目录与目标目录相同" }
        listOf(source, destination).forEach { layout ->
            listOf(layout.videoMetaDir, layout.videoCacheDir, layout.comicsDir).forEach { ensureConfined(layout.root, it) }
        }
        ensure(StorageLocation.SHARED)

        fun collectFiles(dir: File, allowedRoot: File): List<File> {
            if (!dir.exists()) return emptyList()
            ensureConfined(allowedRoot, dir)
            return dir.walkTopDown()
                .onEnter { ensureConfined(allowedRoot, it); true }
                .filter { file -> ensureConfined(allowedRoot, file); file.isFile }
                .toList()
        }

        fun sha256File(file: File): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    coroutineCtx.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest()
        }

        fun filesIdentical(first: File, second: File): Boolean =
            second.isFile && first.length() == second.length() && sha256File(first).contentEquals(sha256File(second))

        val comicFiles = collectFiles(source.comicsDir, source.root)
        val bookFolders = comicFiles.mapNotNull { file ->
            val parts = file.relativeTo(source.comicsDir).path.split(File.separatorChar)
            if (parts.size < 3) null else File(File(source.comicsDir, parts[0]), parts[1])
        }.distinctBy { it.path }

        val preservedBooks = mutableSetOf<String>()
        val completeBookCopies = mutableListOf<Pair<File, File>>()

        for (fromBook in bookFolders) {
            val relative = fromBook.relativeTo(source.comicsDir).path
            val toBook = File(destination.comicsDir, relative)
            ensureConfined(destination.root, toBook)
            val destFiles = collectFiles(toBook, destination.root)
            val srcFiles = collectFiles(fromBook, source.root)
            val sourceComplete = isCompleteComicBook(fromBook)
            when {
                isCompleteComicBook(toBook) -> preservedBooks += relative
                sourceComplete -> completeBookCopies += (fromBook to toBook)
                destFiles.isNotEmpty() -> {
                    val srcNames = srcFiles.map { it.relativeTo(fromBook).path }.toSet()
                    val compatible = destFiles.all { file ->
                        val rel = file.relativeTo(toBook).path
                        rel in srcNames && filesIdentical(File(fromBook, rel), file)
                    }
                    check(compatible) {
                        "同一图集存在冲突的未完成副本，已停止迁移并保留两边文件：$relative"
                    }
                }
            }
        }

        fun shouldSkipPreserved(file: File): Boolean {
            val relative = file.relativeTo(source.comicsDir).path
            if (preservedBooks.any { relative.startsWith(it + File.separator) }) return true
            if (file.parentFile == source.comicsDir && file.extension == "json") {
                val sidecar = readSidecar(file)
                if (sidecar != null && sidecar.kind != DownloadKind.VIDEO) {
                    val key = "${sidecar.siteId.value}${File.separator}${sha256Hex(sidecar.mediaId)}"
                    return key in preservedBooks
                }
            }
            return false
        }

        val copyPairs = listOf(
            source.videoMetaDir to destination.videoMetaDir,
            source.comicsDir to destination.comicsDir,
        ).flatMap { (fromDir, toDir) ->
            collectFiles(fromDir, source.root)
                .filter { fromDir != source.comicsDir || !shouldSkipPreserved(it) }
                .map { src -> src to File(toDir, src.relativeTo(fromDir).path) }
        }
        copyPairs.forEach { (src, dst) ->
            ensureConfined(source.root, src)
            ensureConfined(destination.root, dst)
        }

        // Copy Media3 video caches through their cache owner.
        copyVideos()

        // Remove stale completion markers before overwriting partial destinations.
        for ((_, toBook) in completeBookCopies) {
            val marker = File(toBook, "complete")
            check(!marker.exists() || marker.delete()) { "无法清理旧图集完成标记" }
        }

        val totalBytes = copyPairs.sumOf { it.first.length() }
        var doneBytes = 0L
        var copiedCount = 0
        var skippedCount = 0

        // Commit `complete` markers strictly last after all page payloads pass SHA-256 verification.
        for ((src, dst) in copyPairs.sortedBy { if (it.first.name == "complete") 1 else 0 }) {
            coroutineCtx.ensureActive()
            ensureConfined(source.root, src)
            ensureConfined(destination.root, dst)
            doneBytes += src.length()
            onProgress(doneBytes, totalBytes, src.name)

            if (filesIdentical(src, dst)) {
                skippedCount += 1
                continue
            }

            val parent = dst.parentFile ?: continue
            val temp = File(parent, "${dst.name}.importing")
            ensureConfined(destination.root, temp)
            try {
                check(parent.isDirectory || parent.mkdirs()) { "无法创建目录：${parent.path}" }
                val copyDigest = MessageDigest.getInstance("SHA-256")
                val srcDigest = MessageDigest.getInstance("SHA-256")
                src.inputStream().use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            coroutineCtx.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            srcDigest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                }
                val verifiedTemp = temp.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineCtx.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        copyDigest.update(buffer, 0, count)
                    }
                    copyDigest.digest()
                }
                check(temp.length() == src.length() && srcDigest.digest().contentEquals(verifiedTemp)) {
                    "${src.name} SHA-256 校验不一致"
                }
                if (!temp.renameTo(dst)) {
                    if (dst.exists()) dst.delete()
                    check(temp.renameTo(dst)) { "无法写入迁移文件：${dst.path}" }
                }
                copiedCount += 1
            } catch (e: IOException) {
                throw IOException("${src.name} 复制失败，下载位置未切换：${e.message}", e)
            } finally {
                temp.delete()
            }
        }

        completeBookCopies.forEach { (_, toBook) ->
            check(isCompleteComicBook(toBook)) { "迁移后的图集完整性校验未通过，下载位置未切换" }
        }
        (bookFolders.map { it.relativeTo(source.comicsDir).path } + preservedBooks)
            .forEach { noteSharedGallery(it.replace(File.separatorChar, '/')) }

        coroutineCtx.ensureActive()
        requestDeferredSwitch(StorageLocation.SHARED)

        val mb = doneBytes / (1024L * 1024L)
        StorageMigrationResult(
            copiedFiles = copiedCount,
            skippedFiles = skippedCount,
            totalBytes = doneBytes,
            pendingLocation = StorageLocation.SHARED,
            summary = "已复制 $copiedCount 个文件（跳过 $skippedCount 个），共 $mb MB；重启后生效，应用内原文件保留",
        )
    }

    private fun hasSharedLibraryContent(): Boolean = runCatching {
        if (!isSharedPermissionGranted()) return false
        val root = sharedRoot ?: return false
        val layout = layoutOf(StorageLocation.SHARED)
        val hasBooks = layout.comicsDir.walkTopDown().any { it.isFile && it.name == "book.json" }
        val hasVideos = layout.videoMetaDir.listFiles().orEmpty().any { it.isFile && it.name.endsWith(".json") }
        hasBooks || hasVideos
    }.getOrDefault(false)

    private fun hasPrivateLibraryContent(): Boolean = runCatching {
        val layout = layoutOf(StorageLocation.PRIVATE)
        val hasBooks = layout.comicsDir.walkTopDown().any { it.isFile && it.name == "book.json" }
        val hasVideoMeta = layout.videoMetaDir.listFiles().orEmpty().any { it.isFile && it.name.endsWith(".json") }
        val hasExoSpans = layout.videoCacheDir.walkTopDown().any { it.isFile && it.extension == "exo" }
        hasBooks || hasVideoMeta || hasExoSpans
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun currentVersionCode(): Int = runCatching {
        val appContext = context?.applicationContext ?: return 0
        val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toInt() else info.versionCode
    }.getOrDefault(0)

    private fun atomicWriteText(target: File, text: String) {
        if (runCatching { target.isFile && target.readText() == text }.getOrDefault(false)) return
        val parent = checkNotNull(target.parentFile) { "Invalid sidecar path: ${target.path}" }
        check(parent.isDirectory || parent.mkdirs()) { "无法创建索引目录：${parent.path}" }
        val temp = File(parent, "${target.name}.${System.nanoTime()}.part")
        try {
            temp.writeText(text)
            if (!temp.renameTo(target)) {
                if (target.exists()) target.delete()
                check(temp.renameTo(target)) { "无法写入下载索引：${target.name}" }
            }
        } finally {
            temp.delete()
        }
    }

    companion object {
        const val SHARED_FOLDER_NAME: String = "Raven"
        private const val MAX_SIDECAR_BYTES: Long = 2L * 1024L * 1024L
        private const val SWITCH_FAILED_NOTICE =
            "公共目录不可用，已继续使用应用内存储。现有文件保留，可在下载位置中重试。"

        fun directChild(parent: File, child: File): Boolean = runCatching {
            child.canonicalFile == File(parent.canonicalFile, child.name) &&
                child.absoluteFile.parentFile == parent.absoluteFile
        }.getOrDefault(false)

        fun sha256Hex(raw: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(raw.toByteArray(Charsets.UTF_8))
                .joinToString("") { b -> String.format(Locale.ROOT, "%02x", b) }

        fun safeFileName(raw: String): String {
            val cleaned = raw.map { ch ->
                if (ch.isLetterOrDigit() || ch in "._-") ch else '_'
            }.joinToString("").take(64)
            val hash = sha256Hex(raw).take(8)
            return "$cleaned-$hash"
        }
    }
}
