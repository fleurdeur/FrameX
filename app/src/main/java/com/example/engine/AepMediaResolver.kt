package com.example.engine

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.data.model.LayerType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.abs

enum class AssetMatchStatus {
    MATCHED,
    MISSING,
    MANUAL_RELINKED
}

data class IndexedMediaCandidate(
    val fileName: String,
    val nameLower: String,
    val baseNameLower: String,
    val extensionLower: String,
    val sizeBytes: Long,
    val uriString: String,
    val parentFolderPriority: Int
)

data class ResolvedAssetResult(
    val storedPath: String,
    val extractedFileName: String,
    val status: AssetMatchStatus,
    val matchedUri: String = "",
    val matchedSizeBytes: Long = 0L
)

/**
 * Smart Background Media Asset Resolver & Cached Indexer (`AepMediaResolver`):
 *
 * Resolves external/desktop asset paths stored inside `.aep`, `.kfproject`, and `.xml` files
 * strictly in the required priority order without blocking the UI thread:
 * 1. Extracts the clean filename from the stored path, ignoring Windows `C:\...\` prefixes,
 *    macOS `/Users/.../`, or `file:///` prefixes.
 * 2. Searches the imported file's own folder first (priority 0), then `Downloads`, `DCIM`,
 *    `Movies`, `Pictures`, `Music`, and other common media folders.
 * 3. Matches by filename, preferring exact extension match and closest file size.
 * 4. Caches the scanned index (`cachedIndexEntries`) so the filesystem/MediaStore scan runs **once**.
 * 5. Marks any unresolved asset as [AssetMatchStatus.MISSING] so the user can relink it in Import Preview.
 */
object AepMediaResolver {

    @Volatile
    private var cachedIndexKey: String? = null

    @Volatile
    private var cachedIndexEntries: List<IndexedMediaCandidate> = emptyList()

    private val SUPPORTED_MEDIA_EXTENSIONS = setOf(
        "mp4", "mov", "mkv", "webm", "avi", "m4v",
        "png", "jpg", "jpeg", "webp", "bmp", "gif", "heic",
        "mp3", "wav", "m4a", "aac", "ogg", "flac"
    )

    fun hasAllFilesAccess(context: Context): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            }
        }.getOrDefault(false)
    }

    fun buildManageAllFilesSettingsIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun buildFallbackManageAllFilesIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Step 1: Extracts the clean filename from a stored Windows (`C:\Users\...\clip.mp4`),
     * UNC (`\\Server\Share\img.png`), POSIX, or URI path, ignoring drive prefixes.
     */
    fun extractCleanFileName(storedPath: String): String {
        var clean = storedPath.trim()
            .trim('"', '\'', '\u0000')
        if (clean.isEmpty()) return ""
        // Strip URI query/fragment if present
        val qIdx = clean.indexOf('?')
        if (qIdx >= 0) clean = clean.substring(0, qIdx)
        // Strip Windows drive prefix e.g. C:\ or D:/
        clean = clean.replace(Regex("^[a-zA-Z]:[\\\\/]+"), "")
        // Normalize all backslashes to forward slashes and take the final segment
        val normalized = clean.replace('\\', '/').trimEnd('/')
        val lastSeg = normalized.substringAfterLast('/')
        return Uri.decode(lastSeg).trim()
    }

    fun inferLayerTypeFromFileName(fileName: String): LayerType {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        return when (ext) {
            "mp4", "mov", "mkv", "webm", "avi", "m4v" -> LayerType.VIDEO
            "mp3", "wav", "m4a", "aac", "ogg", "flac" -> LayerType.AUDIO
            else -> LayerType.IMAGE
        }
    }

    @Synchronized
    fun invalidateCache() {
        cachedIndexKey = null
        cachedIndexEntries = emptyList()
    }

    /**
     * Builds (once) or reuses the cached media index across [aepOwnFolder] (priority 0)
     * followed by `Downloads`, `DCIM`, `Movies`, `Pictures`, `Music`, and common folders.
     * Can also be seeded directly in unit tests via [extraSearchRoots].
     */
    suspend fun ensureMediaIndexBuilt(
        context: Context?,
        aepOwnFolder: File? = null,
        extraSearchRoots: List<File> = emptyList(),
        forceRescan: Boolean = false,
        onProgress: (fraction: Float, folderLabel: String) -> Unit = { _, _ -> }
    ): List<IndexedMediaCandidate> = withContext(Dispatchers.IO) {
        val ownPathKey = aepOwnFolder?.absolutePath.orEmpty()
        val extrasKey = extraSearchRoots.joinToString("|") { it.absolutePath }
        val permKey = if (context != null && hasAllFilesAccess(context)) "full" else "scoped"
        val cacheKey = "$permKey::$ownPathKey::$extrasKey"

        if (!forceRescan && cachedIndexKey == cacheKey && cachedIndexEntries.isNotEmpty()) {
            onProgress(1f, "Using cached media index")
            return@withContext cachedIndexEntries
        }

        val orderedRoots = ArrayList<Pair<File, Int>>()
        // 1. AEP's own folder FIRST (priority 0)
        if (aepOwnFolder != null && aepOwnFolder.exists() && aepOwnFolder.isDirectory) {
            orderedRoots.add(aepOwnFolder to 0)
        }
        // 2. Explicit extra roots (priority 1..N)
        extraSearchRoots.forEachIndexed { idx, dir ->
            if (dir.exists() && dir.isDirectory) {
                orderedRoots.add(dir to (1 + idx))
            }
        }
        // 3. Standard Android public folders in strict priority order:
        // Downloads -> DCIM -> Movies -> Pictures -> Music -> Documents / Common
        if (context != null) {
            val stdDirs = listOf(
                Environment.DIRECTORY_DOWNLOADS to 10,
                Environment.DIRECTORY_DCIM to 20,
                Environment.DIRECTORY_MOVIES to 30,
                Environment.DIRECTORY_PICTURES to 40,
                Environment.DIRECTORY_MUSIC to 50,
                Environment.DIRECTORY_DOCUMENTS to 60
            )
            for ((dirConst, prio) in stdDirs) {
                val pubDir = runCatching { Environment.getExternalStoragePublicDirectory(dirConst) }.getOrNull()
                if (pubDir != null && pubDir.exists() && pubDir.isDirectory) {
                    orderedRoots.add(pubDir to prio)
                }
            }
            // App-accessible external media / files directories
            runCatching { context.getExternalFilesDir(null) }.getOrNull()?.let { extAppDir ->
                if (extAppDir.exists()) orderedRoots.add(extAppDir to 65)
            }
        }

        val seenCanonicalPaths = HashSet<String>()
        val collected = ArrayList<IndexedMediaCandidate>(256)
        val totalSteps = (orderedRoots.size + 1).coerceAtLeast(1)

        orderedRoots.forEachIndexed { stepIdx, (rootDir, priority) ->
            currentCoroutineContext().ensureActive()
            onProgress(stepIdx.toFloat() / totalSteps.toFloat(), rootDir.name.ifBlank { "Folder" })
            scanDirectoryBounded(
                dir = rootDir,
                priority = priority,
                maxDepth = if (priority == 0) 4 else 5,
                maxFiles = 4000,
                seenPaths = seenCanonicalPaths,
                out = collected
            )
        }

        // Also index MediaStore entries so scoped-storage devices without MANAGE_EXTERNAL_STORAGE
        // can still resolve Downloads / DCIM / Movies / Pictures / Music when available!
        if (context != null) {
            currentCoroutineContext().ensureActive()
            onProgress((orderedRoots.size.toFloat()) / totalSteps.toFloat(), "Media Library")
            queryMediaStoreCandidates(context, collected, seenCanonicalPaths)
        }

        onProgress(1f, "Completed")
        synchronized(this@AepMediaResolver) {
            cachedIndexKey = cacheKey
            cachedIndexEntries = collected
        }
        collected
    }

    /**
     * Resolves a batch of stored paths against the cached media index on `Dispatchers.IO`.
     * Matches by filename, preferring exact extension match, higher folder priority (AEP folder -> Downloads -> ...),
     * and closest file size when [expectedSizeBytes] > 0.
     */
    suspend fun resolveAssetPaths(
        context: Context?,
        requests: List<Pair<String, Long>>, // (storedPath, expectedSizeBytes)
        aepOwnFolder: File? = null,
        extraSearchRoots: List<File> = emptyList(),
        forceRescan: Boolean = false,
        onProgress: (fraction: Float, label: String) -> Unit = { _, _ -> }
    ): List<ResolvedAssetResult> = withContext(Dispatchers.IO) {
        if (requests.isEmpty()) return@withContext emptyList()

        val index = ensureMediaIndexBuilt(
            context = context,
            aepOwnFolder = aepOwnFolder,
            extraSearchRoots = extraSearchRoots,
            forceRescan = forceRescan,
            onProgress = { p, lbl -> onProgress(p * 0.85f, lbl) }
        )

        val byExactLower = index.groupBy { it.nameLower }
        val byBaseLower = index.groupBy { it.baseNameLower }

        requests.mapIndexed { i, (rawPath, expectedBytes) ->
            currentCoroutineContext().ensureActive()
            onProgress(0.85f + 0.15f * ((i + 1).toFloat() / requests.size.toFloat()), "Matching assets")

            // Already a valid local/content URI that exists
            if (rawPath.startsWith("content://")) {
                val cleanName = extractCleanFileName(rawPath).ifBlank { "Media ${i + 1}" }
                return@mapIndexed ResolvedAssetResult(
                    storedPath = rawPath,
                    extractedFileName = cleanName,
                    status = AssetMatchStatus.MATCHED,
                    matchedUri = rawPath
                )
            }
            if (rawPath.startsWith("file://")) {
                val f = runCatching { File(Uri.parse(rawPath).path ?: "") }.getOrNull()
                if (f != null && f.exists() && f.isFile) {
                    return@mapIndexed ResolvedAssetResult(
                        storedPath = rawPath,
                        extractedFileName = f.name,
                        status = AssetMatchStatus.MATCHED,
                        matchedUri = Uri.fromFile(f).toString(),
                        matchedSizeBytes = f.length()
                    )
                }
            }

            val fileName = extractCleanFileName(rawPath)
            if (fileName.isBlank()) {
                return@mapIndexed ResolvedAssetResult(
                    storedPath = rawPath,
                    extractedFileName = "Missing Asset",
                    status = AssetMatchStatus.MISSING
                )
            }

            val nameLower = fileName.lowercase(Locale.US)
            val extLower = nameLower.substringAfterLast('.', "")
            val baseLower = if (extLower.isNotEmpty()) nameLower.substringBeforeLast('.') else nameLower

            val candidates = byExactLower[nameLower].orEmpty().ifEmpty {
                byBaseLower[baseLower].orEmpty()
            }

            val best = pickBestCandidate(candidates, extLower, expectedBytes)
            if (best != null) {
                ResolvedAssetResult(
                    storedPath = rawPath,
                    extractedFileName = fileName,
                    status = AssetMatchStatus.MATCHED,
                    matchedUri = best.uriString,
                    matchedSizeBytes = best.sizeBytes
                )
            } else {
                ResolvedAssetResult(
                    storedPath = rawPath,
                    extractedFileName = fileName,
                    status = AssetMatchStatus.MISSING
                )
            }
        }
    }

    internal fun pickBestCandidate(
        candidates: List<IndexedMediaCandidate>,
        expectedExtLower: String,
        expectedSizeBytes: Long
    ): IndexedMediaCandidate? {
        if (candidates.isEmpty()) return null
        return candidates.minWithOrNull(
            compareBy<IndexedMediaCandidate>(
                // 1. Prefer exact extension match
                { cand -> if (expectedExtLower.isNotEmpty() && cand.extensionLower == expectedExtLower) 0 else 1 },
                // 2. Prefer higher folder priority (AEP folder 0 -> Downloads 10 -> DCIM 20 -> Movies 30...)
                { cand -> cand.parentFolderPriority },
                // 3. Prefer closest file size when expectedSizeBytes > 0
                { cand ->
                    if (expectedSizeBytes > 0L && cand.sizeBytes > 0L) {
                        abs(cand.sizeBytes - expectedSizeBytes)
                    } else {
                        0L
                    }
                }
            )
        )
    }

    private fun scanDirectoryBounded(
        dir: File,
        priority: Int,
        maxDepth: Int,
        maxFiles: Int,
        seenPaths: MutableSet<String>,
        out: MutableList<IndexedMediaCandidate>,
        depth: Int = 0
    ) {
        if (depth > maxDepth || out.size >= maxFiles) return
        val children = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (child in children) {
            if (out.size >= maxFiles) break
            val name = child.name
            if (name.startsWith(".")) continue
            if (child.isDirectory) {
                if (!name.equals("Android", ignoreCase = true)) {
                    scanDirectoryBounded(child, priority, maxDepth, maxFiles, seenPaths, out, depth + 1)
                }
            } else if (child.isFile) {
                val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
                if (ext in SUPPORTED_MEDIA_EXTENSIONS) {
                    val absPath = child.absolutePath
                    if (seenPaths.add(absPath)) {
                        val lower = name.lowercase(Locale.US)
                        val base = lower.substringBeforeLast('.')
                        out.add(
                            IndexedMediaCandidate(
                                fileName = name,
                                nameLower = lower,
                                baseNameLower = base,
                                extensionLower = ext,
                                sizeBytes = child.length(),
                                uriString = Uri.fromFile(child).toString(),
                                parentFolderPriority = priority
                            )
                        )
                    }
                }
            }
        }
    }

    private fun queryMediaStoreCandidates(
        context: Context,
        out: MutableList<IndexedMediaCandidate>,
        seenKeys: MutableSet<String>
    ) {
        val collections = listOf(
            Triple(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, 28, "video"),
            Triple(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 38, "image"),
            Triple(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, 48, "audio")
        )
        for ((contentUri, defaultPrio, _) in collections) {
            runCatching {
                val projection = buildList {
                    add(MediaStore.MediaColumns._ID)
                    add(MediaStore.MediaColumns.DISPLAY_NAME)
                    add(MediaStore.MediaColumns.SIZE)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        add(MediaStore.MediaColumns.RELATIVE_PATH)
                    }
                }.toTypedArray()

                context.contentResolver.query(
                    contentUri,
                    projection,
                    null,
                    null,
                    "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndex(MediaStore.MediaColumns._ID)
                    val nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    val relCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                    } else -1

                    var count = 0
                    while (cursor.moveToNext() && count < 1500) {
                        count++
                        val id = if (idCol >= 0) cursor.getLong(idCol) else continue
                        val dispName = if (nameCol >= 0) cursor.getString(nameCol) else null
                        if (dispName.isNullOrBlank()) continue
                        val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                        val relPath = if (relCol >= 0) cursor.getString(relCol).orEmpty() else ""
                        val prio = when {
                            relPath.contains("Download", ignoreCase = true) -> 12
                            relPath.contains("DCIM", ignoreCase = true) -> 22
                            relPath.contains("Movies", ignoreCase = true) -> 32
                            relPath.contains("Pictures", ignoreCase = true) -> 42
                            relPath.contains("Music", ignoreCase = true) -> 52
                            else -> defaultPrio
                        }
                        val itemUri = ContentUris.withAppendedId(contentUri, id).toString()
                        if (seenKeys.add(itemUri)) {
                            val lower = dispName.lowercase(Locale.US)
                            val ext = lower.substringAfterLast('.', "")
                            val base = if (ext.isNotEmpty()) lower.substringBeforeLast('.') else lower
                            out.add(
                                IndexedMediaCandidate(
                                    fileName = dispName,
                                    nameLower = lower,
                                    baseNameLower = base,
                                    extensionLower = ext,
                                    sizeBytes = size,
                                    uriString = itemUri,
                                    parentFolderPriority = prio
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    fun resolveParentFolderIfFileUri(uri: Uri): File? {
        if (uri.scheme == "file") {
            val f = uri.path?.let { File(it) }
            val parent = f?.parentFile
            if (parent != null && parent.exists() && parent.isDirectory) return parent
        }
        // Check if DocumentsUI raw primary path is embedded in documentId (e.g. primary:Download/MyEdit/proj.aep)
        val pathStr = Uri.decode(uri.toString())
        val primaryIdx = pathStr.indexOf("primary:")
        if (primaryIdx >= 0) {
            val rel = pathStr.substring(primaryIdx + "primary:".length)
            val extRoot = runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
            if (extRoot != null) {
                val candidateFile = File(extRoot, rel)
                val parent = candidateFile.parentFile
                if (parent != null && parent.exists() && parent.isDirectory) return parent
            }
        }
        return null
    }
}
