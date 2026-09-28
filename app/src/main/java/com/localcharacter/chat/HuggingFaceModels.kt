package com.localcharacter.chat

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class ModelPreset(
    val title: String,
    val subtitle: String,
    val repository: String,
)

data class ResolvedModel(
    val repository: String,
    val fileName: String,
    val downloadUrl: String,
    val expectedSha256: String = "",
    val expectedSize: Long = -1L,
)

data class DownloadProgress(
    val downloaded: Long,
    val total: Long,
    val status: Int,
    val reason: Int,
)

object HuggingFaceModels {
    val presets = listOf(
        ModelPreset(
            title = "Fast Adult Chat — 1.5B (recommended)",
            subtitle = "Much faster than Safeword 4B and better suited to normal phone chat.",
            repository = "mradermacher/Qwen2.5-1.5B-Instruct-uncensored-GGUF",
        ),
        ModelPreset(
            title = "Ultra-fast Chat — 0.5B",
            subtitle = "Fastest option, but simpler replies and less reliable adult roleplay.",
            repository = "Qwen/Qwen2.5-0.5B-Instruct-GGUF",
        ),
        ModelPreset(
            title = "Safeword Casual — 4B (very slow)",
            subtitle = "May take one or two minutes per reply with the free CPU-only engine.",
            repository = "mradermacher/Safeword-Casual-v1-R1-4B-GGUF",
        ),
    )

    suspend fun resolveQ4Km(repository: String): ResolvedModel = withContext(Dispatchers.IO) {
        val connection = (URL("https://huggingface.co/api/models/$repository?blobs=true").openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "PrivateCharacterChat/1.8")
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("Model information request failed with HTTP $code.")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(body)
            val siblings = root.optJSONArray("siblings")
                ?: throw IllegalStateException("The model repository did not provide a file list.")

            val candidates = buildList {
                for (i in 0 until siblings.length()) {
                    val item = siblings.getJSONObject(i)
                    val name = item.optString("rfilename", "")
                    if (
                        name.endsWith(".gguf", ignoreCase = true) &&
                        name.contains("Q4_K_M", ignoreCase = true) &&
                        !name.contains("-00001-of-", ignoreCase = true)
                    ) add(item)
                }
            }

            val selectedItem = candidates.firstOrNull()
                ?: throw IllegalStateException("No single-file Q4_K_M GGUF was found in this repository.")
            val selected = selectedItem.getString("rfilename")
            val lfs = selectedItem.optJSONObject("lfs")
            val expectedHash = lfs?.optString("sha256", "").orEmpty()
            val expectedSize = lfs?.optLong("size", -1L) ?: -1L

            val encodedPath = selected.split("/").joinToString("/") {
                URLEncoder.encode(it, Charsets.UTF_8.name()).replace("+", "%20")
            }
            ResolvedModel(
                repository = repository,
                fileName = selected.substringAfterLast("/"),
                downloadUrl = "https://huggingface.co/$repository/resolve/main/$encodedPath?download=true",
                expectedSha256 = expectedHash,
                expectedSize = expectedSize,
            )
        } finally {
            connection.disconnect()
        }
    }

    fun enqueueDownload(context: Context, model: ResolvedModel): Pair<Long, File> {
        val targetDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IllegalStateException("External app storage is unavailable.")
        val target = File(targetDir, model.fileName)
        if (target.exists()) target.delete()

        val request = DownloadManager.Request(Uri.parse(model.downloadUrl))
            .setTitle("Downloading local AI model")
            .setDescription(model.fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, model.fileName)

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return manager.enqueue(request) to target
    }

    suspend fun awaitDownload(
        context: Context,
        id: Long,
        model: ResolvedModel,
        onProgress: (DownloadProgress) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        while (true) {
            val cursor: Cursor = manager.query(DownloadManager.Query().setFilterById(id))
            cursor.use {
                if (!it.moveToFirst()) throw IllegalStateException("The Android download was removed.")
                val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                val downloaded = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                onProgress(DownloadProgress(downloaded, total, status, reason))

                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        val localUri = it.getString(it.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                        val path = Uri.parse(localUri).path
                            ?: throw IllegalStateException("Android did not return the downloaded file path.")
                        val file = File(path)
                        if (model.expectedSize > 0 && file.length() != model.expectedSize) {
                            file.delete()
                            throw IllegalStateException("Downloaded model size verification failed.")
                        }
                        if (model.expectedSha256.isNotBlank()) {
                            val digest = java.security.MessageDigest.getInstance("SHA-256")
                            file.inputStream().buffered(1024 * 1024).use { input ->
                                val buffer = ByteArray(1024 * 1024)
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    digest.update(buffer, 0, read)
                                }
                            }
                            val actual = digest.digest().joinToString("") { "%02x".format(it) }
                            if (!actual.equals(model.expectedSha256, ignoreCase = true)) {
                                file.delete()
                                throw IllegalStateException("Downloaded model checksum verification failed.")
                            }
                        }
                        return@withContext file
                    }
                    DownloadManager.STATUS_FAILED ->
                        throw IllegalStateException("Model download failed. Android reason code: $reason")
                }
            }
            delay(750)
        }
        @Suppress("UNREACHABLE_CODE")
        throw IllegalStateException("Download monitoring ended unexpectedly.")
    }
}
