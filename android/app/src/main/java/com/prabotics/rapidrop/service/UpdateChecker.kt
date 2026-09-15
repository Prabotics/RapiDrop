package com.prabotics.rapidrop.service

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL



sealed class UpdateResult {
    data class UpdateAvailable(
        val latestVersion: String,
        val releaseNotes: String,
        val releaseUrl: String
    ) : UpdateResult()
    data class UpToDate(val currentVersion: String) : UpdateResult()
    data class Error(val message: String) : UpdateResult()
}

object UpdateChecker {
    const val CURRENT_VERSION = "1.0.0"
    private const val GITHUB_RELEASES_URL = "https://api.github.com/repos/Prabotics/RapiDrop/releases/latest"

    suspend fun checkForUpdates(context: Context): UpdateResult = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val prefs = context.getSharedPreferences("rapidrop_updates", Context.MODE_PRIVATE)
            val storedEtag = prefs.getString("last_etag", null)

            val url = URL(GITHUB_RELEASES_URL)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", "RapiDrop/$CURRENT_VERSION")
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                if (!storedEtag.isNullOrBlank()) {
                    setRequestProperty("If-None-Match", storedEtag)
                }
            }

            val responseCode = connection.responseCode
            if (responseCode == 304 || responseCode == 404) {
                return@withContext UpdateResult.UpToDate(CURRENT_VERSION)
            }
            if (responseCode !in 200..299) {
                return@withContext UpdateResult.Error("HTTP $responseCode")
            }

            val newEtag = connection.getHeaderField("ETag")
            if (!newEtag.isNullOrBlank()) {
                prefs.edit().putString("last_etag", newEtag).apply()
            }

            val jsonString = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(jsonString)
            val tagName = json.optString("tag_name", "").trim()
            val releaseNotes = json.optString("body", "").trim()
            val releaseUrl = json.optString("html_url", "https://github.com/Prabotics/RapiDrop/releases")

            if (isNewerVersion(tagName, CURRENT_VERSION)) {
                UpdateResult.UpdateAvailable(
                    latestVersion = tagName,
                    releaseNotes = releaseNotes,
                    releaseUrl = releaseUrl
                )
            } else {
                UpdateResult.UpToDate(CURRENT_VERSION)
            }
        } catch (_: IOException) {
            UpdateResult.Error("Unable to connect")
        } catch (_: org.json.JSONException) {
            UpdateResult.Error("Invalid response")
        } catch (_: SecurityException) {
            UpdateResult.Error("Network permission denied")
        } finally {
            connection?.disconnect()
        }
    }

    fun isNewerVersion(latestTag: String, current: String): Boolean {
        if (latestTag.isBlank()) return false
        val cleanLatest = latestTag.removePrefix("v").removePrefix("V").trim()
        val cleanCurrent = current.removePrefix("v").removePrefix("V").trim()
        val latestParts = cleanLatest.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = cleanCurrent.split(".").mapNotNull { it.toIntOrNull() }
        if (latestParts.isEmpty() || currentParts.isEmpty()) return false
        val maxLen = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }
}
