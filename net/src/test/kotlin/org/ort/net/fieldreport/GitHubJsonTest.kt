package org.ort.net.fieldreport

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Exercises [GitHubJson] against real, shortened GitHub API response shapes — never a general
 * JSON parser, only the four scalar fields [org.ort.net.fieldreport.real.RealFieldReportUploadClient] reads. */
class GitHubJsonTest {

    private val repoResponse = """
        {
          "id": 123456,
          "name": "offline-radio-transcriber",
          "full_name": "NiyaNagi/offline-radio-transcriber",
          "private": false,
          "html_url": "https://github.com/NiyaNagi/offline-radio-transcriber"
        }
    """.trimIndent()

    @Test
    fun `FR_OBS_10 extracts a boolean field from a real-shaped repo response`() {
        assertEquals(false, GitHubJson.extractBoolean(repoResponse, "private"))
    }

    @Test
    fun `FR_OBS_10 extracts true for a private repo`() {
        val body = repoResponse.replace("\"private\": false", "\"private\": true")
        assertEquals(true, GitHubJson.extractBoolean(body, "private"))
    }

    @Test
    fun `extractBoolean is null when the key is absent`() {
        assertNull(GitHubJson.extractBoolean(repoResponse, "no_such_key"))
    }

    @Test
    fun `extractLong reads a real-shaped release id`() {
        val body = """{"id": 987654321, "tag_name": "field-reports"}"""
        assertEquals(987654321L, GitHubJson.extractLong(body, "id"))
    }

    @Test
    fun `extractLong is null when the key is absent`() {
        assertNull(GitHubJson.extractLong("""{"tag_name": "field-reports"}""", "id"))
    }

    @Test
    fun `extractString reads a real-shaped download url`() {
        val body = """{"browser_download_url": "https://github.com/o/r/releases/download/t/f.zip"}"""
        assertEquals(
            "https://github.com/o/r/releases/download/t/f.zip",
            GitHubJson.extractString(body, "browser_download_url"),
        )
    }

    @Test
    fun `extractString unescapes a quoted issue title`() {
        val body = """{"title": "Field report: \"weird\" build"}"""
        assertEquals("""Field report: "weird" build""", GitHubJson.extractString(body, "title"))
    }

    @Test
    fun `extractString is null when the key is absent`() {
        assertNull(GitHubJson.extractString(repoResponse, "no_such_key"))
    }

    @Test
    fun `jsonEscape round-trips through extractString for special characters`() {
        val original = "line one\nline \"two\"\tand a backslash \\ here"
        val body = """{"body": "${GitHubJson.jsonEscape(original)}"}"""
        assertEquals(original, GitHubJson.extractString(body, "body"))
    }
}
