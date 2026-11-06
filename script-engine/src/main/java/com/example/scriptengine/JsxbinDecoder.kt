package com.example.scriptengine

/**
 * In-memory `@JSXBIN@ES@2.0@` binary reader & symbol/AST extractor.
 *
 * STRICT SECURITY POLICY:
 * - Decoded symbols and AST nodes stay strictly private in memory for execution & compatibility probing.
 * - NEVER writes decoded output to disk, NEVER logs decoded strings/identifiers, and NEVER includes
 *   decoded source in `toString()`, exception messages, or compatibility reports.
 */
object JsxbinDecoder {

    private val SUPPORTED_VERSIONS = setOf("1.0", "2.0", "2.1")

    sealed interface DecodeOutcome {
        data class Success(
            val version: String,
            internal val binarySession: JsxbinInMemorySession
        ) : DecodeOutcome {
            override fun toString(): String = "JsxbinDecodeOutcome.Success(version=$version)"
        }

        data class UnsupportedVersion(val version: String) : DecodeOutcome
        data class Corrupted(val reason: String) : DecodeOutcome
    }

    /**
     * Opaque in-memory holder whose `toString()` never reveals internal symbols.
     */
    class JsxbinInMemorySession internal constructor(
        val version: String,
        internal val extractedSymbols: Set<String>,
        internal val nodeCount: Int
    ) {
        override fun toString(): String = "JsxbinInMemorySession(v=$version, nodes=$nodeCount)"
    }

    fun isJsxbinContent(rawText: String): Boolean {
        val trimmed = rawText.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        return trimmed.startsWith("@JSXBIN@ES@")
    }

    fun decodeInMemory(rawBytes: ByteArray): DecodeOutcome {
        val rawText = rawBytes.toString(Charsets.ISO_8859_1)
            .trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        if (!rawText.startsWith("@JSXBIN@ES@")) {
            return DecodeOutcome.Corrupted("Missing @JSXBIN@ES@ header")
        }
        val afterPrefix = rawText.substring("@JSXBIN@ES@".length)
        val atIdx = afterPrefix.indexOf('@')
        if (atIdx <= 0 || atIdx > 16) {
            return DecodeOutcome.Corrupted("Malformed JSXBIN version delimiter")
        }
        val version = afterPrefix.substring(0, atIdx).trim()
        if (version !in SUPPORTED_VERSIONS) {
            return DecodeOutcome.UnsupportedVersion(version.ifBlank { "unknown" })
        }

        val payload = buildString(afterPrefix.length - atIdx) {
            for (i in (atIdx + 1) until afterPrefix.length) {
                val c = afterPrefix[i]
                if ((c in 'A'..'Z') || (c in 'a'..'z') || (c in '0'..'9')) {
                    append(c)
                }
            }
        }
        if (payload.length < 3) {
            return DecodeOutcome.Corrupted("Truncated JSXBIN payload")
        }

        return runCatching {
            val extracted = scanVlqAsciiStringsAndNodes(payload)
            DecodeOutcome.Success(
                version = version,
                binarySession = JsxbinInMemorySession(
                    version = version,
                    extractedSymbols = extracted.first,
                    nodeCount = extracted.second
                )
            )
        }.getOrElse {
            DecodeOutcome.Corrupted("Invalid JSXBIN stream")
        }
    }

    /**
     * Decodes JSXBIN's 5-bit base-32 VLQ runs in memory to recover symbol/identifier tokens
     * needed for DOM/ScriptUI/Effect compatibility analysis and runtime binding without ever
     * materializing or persisting reconstructed source code.
     *
     * Encoding properties:
     * - Terminal 5-bit values (0..31): 'A'..'Z' -> 0..25, 'a'..'f' -> 26..31
     * - Continuation prefix digits: 'g'..'z' -> 0..19, '0'..'9' -> 20..29
     *   e.g. 'i' (2) + 'B' (1) = 65 ('A'), 'j' (3) + 'B' (1) = 97 ('a').
     */
    private fun scanVlqAsciiStringsAndNodes(payload: String): Pair<Set<String>, Int> {
        val values = IntArray(payload.length)
        var vLen = 0
        var idx = 0
        val n = payload.length
        while (idx < n) {
            var acc = 0
            var steps = 0
            while (idx < n && steps < 7) {
                val ch = payload[idx++]
                val term = when (ch) {
                    in 'A'..'Z' -> ch - 'A'
                    in 'a'..'f' -> 26 + (ch - 'a')
                    else -> -1
                }
                if (term >= 0) {
                    acc = (acc shl 5) or term
                    break
                } else {
                    val cont = when (ch) {
                        in 'g'..'z' -> ch - 'g'
                        in '0'..'9' -> 20 + (ch - '0')
                        else -> 0
                    }
                    acc = (acc shl 5) or cont
                    steps++
                }
            }
            values[vLen++] = acc
        }

        val symbols = LinkedHashSet<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < vLen) {
            val lenCandidate = values[i]
            if (lenCandidate in 3..64 && i + lenCandidate < vLen) {
                sb.setLength(0)
                var validAscii = true
                for (k in 1..lenCandidate) {
                    val code = values[i + k]
                    if (code in 32..126) {
                        sb.append(code.toChar())
                    } else {
                        validAscii = false
                        break
                    }
                }
                if (validAscii && sb.any { it.isLetter() }) {
                    val token = sb.toString()
                    if (isRelevantHostOrUiSymbol(token)) {
                        symbols.add(token)
                    }
                    i += lenCandidate + 1
                    continue
                }
            }
            i++
        }

        // Also scan contiguous printable runs >= 4 chars encoded via i/j/h pairs
        sb.setLength(0)
        for (k in 0 until vLen) {
            val code = values[k]
            if (code in 32..126) {
                sb.append(code.toChar())
            } else {
                if (sb.length in 4..80 && sb.any { it.isLetter() }) {
                    val candidate = sb.toString()
                    extractKnownHostTokens(candidate, symbols)
                }
                sb.setLength(0)
            }
        }
        if (sb.length in 4..80 && sb.any { it.isLetter() }) {
            extractKnownHostTokens(sb.toString(), symbols)
        }

        return symbols to vLen.coerceAtLeast(1)
    }

    private val KNOWN_HOST_KEYWORDS = listOf(
        "Panel", "Window", "ScriptUI", "graphics", "newPen", "newBrush", "newPath",
        "customBoundedValue", "dropdownlist", "slider", "checkbox", "radiobutton",
        "edittext", "statictext", "button", "tabbedpanel", "progressbar", "listbox",
        "CompItem", "AVLayer", "TextLayer", "ShapeLayer", "CameraLayer", "TextDocument",
        "KeyframeEase", "KeyframeInterpolationType", "MarkerValue", "PropertyGroup",
        "ADBE Text Properties", "ADBE Text Document", "ADBE Text Animators",
        "ADBE Text Animator", "ADBE Text Animator Properties", "ADBE Text Selector",
        "ADBE Text Percent Start", "ADBE Text Percent End", "ADBE Text Percent Offset",
        "ADBE Text Position 3D", "ADBE Text Scale 3D", "ADBE Text Rotation",
        "ADBE Text Opacity", "ADBE Text Tracking Amount", "ADBE Text Fill Color",
        "beginUndoGroup", "endUndoGroup", "activeItem", "selectedLayers", "selectedProperties",
        "selectedKeys", "setTemporalEaseAtKey", "setValueAtTime", "expression",
        "settings", "preferences", "File", "Folder", "Socket", "system.callSystem"
    )

    private fun isRelevantHostOrUiSymbol(token: String): Boolean {
        return KNOWN_HOST_KEYWORDS.any { token.contains(it, ignoreCase = false) }
    }

    private fun extractKnownHostTokens(haystack: String, out: MutableSet<String>) {
        for (kw in KNOWN_HOST_KEYWORDS) {
            if (haystack.contains(kw)) {
                out.add(kw)
            }
        }
    }
}
