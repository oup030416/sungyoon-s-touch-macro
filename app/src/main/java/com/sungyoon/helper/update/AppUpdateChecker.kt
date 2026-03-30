package com.sungyoon.helper.update

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object AppUpdateChecker {
    private const val OWNER = "oup030416"
    private const val REPO = "sungyoon-s-touch-macro"
    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    fun fetchLatestRelease(): AppUpdateInfo? {
        val connection = (URL(LATEST_RELEASE_URL).openConnection() as? HttpURLConnection) ?: return null
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 7000
            connection.readTimeout = 7000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")

            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) return null

            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parseRelease(JSONObject(body))
        } catch (_: Throwable) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun parseRelease(json: JSONObject): AppUpdateInfo? {
        val tagName = json.optString("tag_name")
        val releaseName = json.optString("name")
        val releaseBody = json.optString("body")
        val assets = json.optJSONArray("assets") ?: return null

        var downloadUrl: String? = null
        var assetName: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            if (name.endsWith(".apk", ignoreCase = true)) {
                downloadUrl = asset.optString("browser_download_url")
                assetName = name
                break
            }
        }

        val versionCode = extractVersionCode(
            releaseBody = releaseBody,
            tagName = tagName,
            releaseName = releaseName,
            assetName = assetName.orEmpty()
        ) ?: return null

        val versionName = extractVersionName(
            releaseBody = releaseBody,
            tagName = tagName,
            releaseName = releaseName,
            fallback = tagName.ifBlank { releaseName }
        )

        return AppUpdateInfo(
            versionCode = versionCode,
            versionName = versionName,
            downloadUrl = downloadUrl ?: return null,
            releaseNotes = releaseBody.trim()
        )
    }

    private fun extractVersionCode(
        releaseBody: String,
        tagName: String,
        releaseName: String,
        assetName: String
    ): Int? {
        val patterns = listOf(
            Regex("""(?im)^versionCode\s*[:=]\s*(\d+)\s*$"""),
            Regex("""(?im)^devVersionCode\s*[:=]\s*(\d+)\s*$"""),
            Regex("""(?im)^code\s*[:=]\s*(\d+)\s*$"""),
            Regex("""(?:\+|_|-)(\d+)$"""),
            Regex("""\((\d+)\)$""")
        )

        val candidates = listOf(releaseBody, tagName, releaseName, assetName)
        for (candidate in candidates) {
            for (pattern in patterns) {
                val match = pattern.find(candidate) ?: continue
                val value = match.groupValues.getOrNull(1)?.toIntOrNull() ?: continue
                return value
            }
        }
        return null
    }

    private fun extractVersionName(
        releaseBody: String,
        tagName: String,
        releaseName: String,
        fallback: String
    ): String {
        val explicit = Regex("""(?im)^versionName\s*[:=]\s*([^\r\n]+)$""").find(releaseBody)
            ?.groupValues?.getOrNull(1)
            ?.trim()
        if (!explicit.isNullOrBlank()) return explicit

        val candidate = listOf(releaseName, tagName, fallback)
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
            .trim()
        return candidate.ifBlank { "최신 버전" }
    }
}
