package org.ort.net.fieldreport

/**
 * The minimal JSON reading/writing
 * [RealFieldReportUploadClient][org.ort.net.fieldreport.real.RealFieldReportUploadClient] needs
 * against the GitHub REST API — no dependency added: `net/build.gradle.kts` depends on
 * `:core` only, and adding a JSON library here for four scalar fields (`private`, `id`, `html_url`,
 * `browser_download_url`) would be the same shape of unnecessary dependency this module's own
 * README already argues against for HTTP clients. `org.json.JSONObject` (Android's built-in JSON
 * API) was deliberately not used either: `:net`'s unit tests run on a plain host JVM with no
 * Robolectric (`net/build.gradle.kts` — JUnit5 only), where `org.json`'s Android stub classes throw
 * `RuntimeException: Stub!` at runtime rather than doing anything.
 *
 * Correctness is scoped to exactly what this module reads and writes: single, unnested, top-level
 * string/boolean/integer fields on GitHub's own response and request shapes — never general JSON
 * (no arrays, no nested objects). [GitHubJsonTest] exercises every extractor against real,
 * shortened GitHub API response bodies.
 */
internal object GitHubJson {

    /** Escapes [value] for use inside a JSON string literal this module writes as a request body. */
    fun jsonEscape(value: String): String = buildString {
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }

    /** The first top-level `"key": "value"` string field named [key] in [json], unescaped — `null`
     * if [key] is absent or its value is not a JSON string. */
    fun extractString(json: String, key: String): String? {
        val regex = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        return regex.find(json)?.groupValues?.get(1)?.let(::unescape)
    }

    /** The first top-level `"key": true`/`"key": false` field named [key] in [json] — `null` if
     * [key] is absent or its value is not a JSON boolean literal. */
    fun extractBoolean(json: String, key: String): Boolean? {
        val regex = Regex("\"${Regex.escape(key)}\"\\s*:\\s*(true|false)")
        return when (regex.find(json)?.groupValues?.get(1)) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    /** The first top-level `"key": <integer>` field named [key] in [json] — `null` if [key] is
     * absent or its value is not a JSON integer literal. */
    fun extractLong(json: String, key: String): Long? {
        val regex = Regex("\"${Regex.escape(key)}\"\\s*:\\s*(-?\\d+)")
        return regex.find(json)?.groupValues?.get(1)?.toLongOrNull()
    }

    private fun unescape(raw: String): String = buildString {
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                when (raw[i + 1]) {
                    '"' -> append('"')
                    '\\' -> append('\\')
                    '/' -> append('/')
                    'n' -> append('\n')
                    'r' -> append('\r')
                    't' -> append('\t')
                    'u' -> {
                        val hex = raw.substring(i + 2, minOf(i + 6, raw.length))
                        hex.toIntOrNull(16)?.let { append(it.toChar()) }
                        i += 4
                    }
                    else -> append(raw[i + 1])
                }
                i += 2
            } else {
                append(c)
                i += 1
            }
        }
    }
}
