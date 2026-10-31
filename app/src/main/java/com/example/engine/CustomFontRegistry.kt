package com.example.engine

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.res.ResourcesCompat
import com.example.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

data class StudioFontOption(
    val displayName: String,
    val isCustomImported: Boolean = false,
    val fontResId: Int? = null,
    val localFilePath: String? = null
)

/**
 * Bundled Google Fonts + User-Imported Custom Font (.ttf / .otf) Registry for FramePollution:
 * - Resolves bundled res/font assets (`Space Grotesk`, `Bebas Neue`, `Montserrat`, `Oswald`,
 *   `Playfair Display`, `Pacifico`, `JetBrains Mono`) and system families (`Sans Serif`, `Serif`, `Monospace`).
 * - Allows users to import any `.ttf` or `.otf` font file via Android's Storage Access Framework (`OpenDocument`
 *   with zero storage permissions), persists it in `filesDir/custom_fonts/`, validates it with `Typeface.createFromFile`,
 *   and renders it across OpenGL ES 2.0, Pre-Render, Effect Thumbnails, Compose UI, and MP4 Export!
 */
object CustomFontRegistry {

    private const val CUSTOM_FONT_DIR = "custom_fonts"

    val bundledFonts: List<StudioFontOption> = listOf(
        StudioFontOption("Space Grotesk", fontResId = R.font.space_grotesk),
        StudioFontOption("Bebas Neue", fontResId = R.font.bebas_neue),
        StudioFontOption("Montserrat", fontResId = R.font.montserrat),
        StudioFontOption("Oswald", fontResId = R.font.oswald),
        StudioFontOption("Playfair Display", fontResId = R.font.playfair_display),
        StudioFontOption("Pacifico", fontResId = R.font.pacifico),
        StudioFontOption("JetBrains Mono", fontResId = R.font.jetbrains_mono),
        StudioFontOption("Sans Serif"),
        StudioFontOption("Serif"),
        StudioFontOption("Monospace")
    )

    private val typefaceCache = ConcurrentHashMap<String, Typeface>()
    private val customFontFilesByName = ConcurrentHashMap<String, String>()

    @Volatile
    private var scannedDiskOnce: Boolean = false

    private val _customFonts = MutableStateFlow<List<StudioFontOption>>(emptyList())
    val customFonts: StateFlow<List<StudioFontOption>> = _customFonts.asStateFlow()

    fun ensureLoaded(context: Context?) {
        if (scannedDiskOnce || context == null) return
        synchronized(this) {
            if (scannedDiskOnce) return
            scanCustomFontDirectory(context.applicationContext)
            scannedDiskOnce = true
        }
    }

    fun allAvailableFonts(context: Context?): List<StudioFontOption> {
        ensureLoaded(context)
        return bundledFonts + _customFonts.value
    }

    /**
     * Imports a `.ttf` or `.otf` font picked by the user, validates that Android's FreeType engine
     * can parse it, saves it permanently in `filesDir/custom_fonts/`, and returns its display name.
     */
    suspend fun importFontFromUri(context: Context, uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        ensureLoaded(appCtx)
        runCatching {
            val rawName = queryFileName(appCtx, uri) ?: "ImportedFont_${System.currentTimeMillis() % 10000}.ttf"
            val ext = rawName.substringAfterLast('.', "ttf").lowercase().let {
                if (it == "otf" || it == "ttf") it else "ttf"
            }
            val cleanStem = rawName
                .substringBeforeLast('.')
                .replace(Regex("[^a-zA-Z0-9 _\\-]"), " ")
                .trim()
                .take(26)
                .ifBlank { "Custom Font" }

            val fontDir = File(appCtx.filesDir, CUSTOM_FONT_DIR).apply { mkdirs() }
            val safeFileStem = cleanStem.replace(Regex("\\s+"), "_")
            val destFile = File(fontDir, "$safeFileStem.$ext")

            val copied = appCtx.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
                true
            } ?: false

            if (!copied || !destFile.exists() || destFile.length() < 64L) {
                destFile.delete()
                throw IllegalArgumentException("Selected font file could not be read.")
            }

            val parsedTypeface = Typeface.createFromFile(destFile)
            if (parsedTypeface == null || parsedTypeface === Typeface.DEFAULT) {
                // Verify file header magic for TTF (0x00010000 / 'true') or OTF ('OTTO')
                val header = ByteArray(4)
                val readBytes = destFile.inputStream().use { it.read(header) }
                val isValidMagic = readBytes == 4 && (
                    (header[0] == 0.toByte() && header[1] == 1.toByte() && header[2] == 0.toByte() && header[3] == 0.toByte()) ||
                        String(header, Charsets.US_ASCII).let { it == "OTTO" || it == "true" || it == "ttcf" }
                    )
                if (!isValidMagic) {
                    destFile.delete()
                    throw IllegalArgumentException("Unsupported font format. Please choose a .ttf or .otf file.")
                }
            }

            if (parsedTypeface != null) {
                typefaceCache[cleanStem] = parsedTypeface
            }
            customFontFilesByName[cleanStem] = destFile.absolutePath
            refreshCustomFontFlow()
            cleanStem
        }
    }

    /**
     * Registers an already-existing local `.ttf` or `.otf` file directly (useful for testing & presets).
     */
    fun registerLocalFontFile(displayName: String, fontFile: File): Boolean {
        if (!fontFile.exists() || fontFile.length() <= 0L) return false
        val tf = runCatching { Typeface.createFromFile(fontFile) }.getOrNull() ?: return false
        val clean = displayName.trim().ifBlank { fontFile.nameWithoutExtension }
        typefaceCache[clean] = tf
        customFontFilesByName[clean] = fontFile.absolutePath
        refreshCustomFontFlow()
        return true
    }

    fun resolveTypeface(
        context: Context?,
        fontFamilyName: String,
        boldFallback: Boolean = true
    ): Typeface {
        val key = fontFamilyName.trim().ifBlank { "Space Grotesk" }
        typefaceCache[key]?.let { return it }

        ensureLoaded(context)

        // 1. Check user-imported custom fonts on disk
        customFontFilesByName[key]?.let { path ->
            val f = File(path)
            if (f.exists()) {
                val tf = runCatching { Typeface.createFromFile(f) }.getOrNull()
                if (tf != null) {
                    typefaceCache[key] = tf
                    return tf
                }
            }
        }

        // 2. Check bundled res/font Google Fonts
        val bundled = bundledFonts.firstOrNull { it.displayName.equals(key, ignoreCase = true) }
        if (bundled?.fontResId != null && context != null) {
            val loaded = runCatching { ResourcesCompat.getFont(context, bundled.fontResId) }.getOrNull()
            if (loaded != null) {
                typefaceCache[key] = loaded
                return loaded
            }
        }

        // 3. System fallback families
        val fallback = when {
            key.equals("Serif", ignoreCase = true) || key.equals("Playfair Display", ignoreCase = true) ->
                Typeface.create(Typeface.SERIF, if (boldFallback) Typeface.BOLD else Typeface.NORMAL)
            key.equals("Monospace", ignoreCase = true) || key.equals("JetBrains Mono", ignoreCase = true) ->
                Typeface.create(Typeface.MONOSPACE, if (boldFallback) Typeface.BOLD else Typeface.NORMAL)
            else ->
                Typeface.create(Typeface.SANS_SERIF, if (boldFallback) Typeface.BOLD else Typeface.NORMAL)
        }
        if (context != null) {
            typefaceCache[key] = fallback
        }
        return fallback
    }

    fun resolveComposeFontFamily(context: Context?, fontFamilyName: String): FontFamily {
        val bundled = bundledFonts.firstOrNull { it.displayName.equals(fontFamilyName, ignoreCase = true) }
        if (bundled?.fontResId != null) {
            return FontFamily(Font(bundled.fontResId, FontWeight.Bold))
        }
        val tf = resolveTypeface(context, fontFamilyName)
        return FontFamily(tf)
    }

    private fun scanCustomFontDirectory(appContext: Context) {
        val dir = File(appContext.filesDir, CUSTOM_FONT_DIR)
        if (!dir.exists()) return
        dir.listFiles()?.forEach { file ->
            val ext = file.extension.lowercase()
            if ((ext == "ttf" || ext == "otf") && file.length() > 64L) {
                val displayName = file.nameWithoutExtension.replace('_', ' ').trim()
                val tf = runCatching { Typeface.createFromFile(file) }.getOrNull()
                if (tf != null && displayName.isNotBlank()) {
                    customFontFilesByName[displayName] = file.absolutePath
                    typefaceCache[displayName] = tf
                }
            }
        }
        refreshCustomFontFlow()
    }

    private fun refreshCustomFontFlow() {
        _customFonts.value = customFontFilesByName.entries
            .sortedBy { it.key.lowercase() }
            .map { (name, path) ->
                StudioFontOption(
                    displayName = name,
                    isCustomImported = true,
                    localFilePath = path
                )
            }
    }

    private fun queryFileName(context: Context, uri: Uri): String? {
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) {
                            val name = c.getString(idx)?.trim()
                            if (!name.isNullOrBlank()) return name
                        }
                    }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
    }
}
