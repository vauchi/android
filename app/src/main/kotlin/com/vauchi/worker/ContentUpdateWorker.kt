package com.vauchi.worker

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Background worker for checking and applying content updates.
 *
 * Runs periodically to check for updates to networks, locales, themes, and help content.
 */
class ContentUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "ContentUpdateWorker"
        const val WORK_NAME = "vauchi_content_update"

        private const val PREFS_NAME = "vauchi_content"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_CONTENT_URL = "content_url"
        private const val KEY_LAST_CHECK = "last_check"
        private const val KEY_CACHED_MANIFEST = "cached_manifest"

        private const val DEFAULT_CONTENT_URL = "https://vauchi.app/app-files/"

        /**
         * Schedule periodic content update checks.
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<ContentUpdateWorker>(
                1, TimeUnit.HOURS
            ).setConstraints(constraints)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )

            Log.d(TAG, "Scheduled periodic content update checks")
        }

        /**
         * Cancel scheduled content update checks.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Cancelled content update checks")
        }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val prefs: SharedPreferences by lazy {
        applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting content update check")

        if (!isEnabled()) {
            Log.d(TAG, "Content updates disabled, skipping")
            return@withContext Result.success()
        }

        try {
            val manifest = fetchManifest()
            val updates = findUpdates(manifest)

            if (updates.isEmpty()) {
                Log.d(TAG, "No content updates available")
                updateLastCheckTime()
                return@withContext Result.success()
            }

            Log.d(TAG, "Found ${updates.size} content updates: $updates")

            var applied = 0
            var failed = 0

            for (type in updates) {
                try {
                    downloadAndCache(type, manifest)
                    applied++
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update $type: ${e.message}")
                    failed++
                }
            }

            // Save manifest after updates
            saveManifest(manifest)
            updateLastCheckTime()

            Log.d(TAG, "Content update complete: $applied applied, $failed failed")
            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Content update check failed: ${e.message}", e)
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    private fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)

    private fun getContentUrl(): String =
        prefs.getString(KEY_CONTENT_URL, DEFAULT_CONTENT_URL) ?: DEFAULT_CONTENT_URL

    private fun updateLastCheckTime() {
        prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
    }

    private suspend fun fetchManifest(): ContentManifest = withContext(Dispatchers.IO) {
        val url = URL("${getContentUrl()}manifest.json")
        val text = url.readText()
        json.decodeFromString(ContentManifest.serializer(), text)
    }

    private fun getCachedManifest(): ContentManifest? {
        val manifestJson = prefs.getString(KEY_CACHED_MANIFEST, null) ?: return null
        return try {
            json.decodeFromString(ContentManifest.serializer(), manifestJson)
        } catch (e: Exception) {
            null
        }
    }

    private fun saveManifest(manifest: ContentManifest) {
        val manifestJson = json.encodeToString(ContentManifest.serializer(), manifest)
        prefs.edit().putString(KEY_CACHED_MANIFEST, manifestJson).apply()
    }

    private fun findUpdates(remote: ContentManifest): List<ContentType> {
        val cached = getCachedManifest()
        val updates = mutableListOf<ContentType>()

        // Check networks
        remote.content.networks?.let { remoteEntry ->
            if (cached?.content?.networks?.version != remoteEntry.version) {
                updates.add(ContentType.NETWORKS)
            }
        }

        // Check locales
        remote.content.locales?.let { remoteEntry ->
            if (cached?.content?.locales?.version != remoteEntry.version) {
                updates.add(ContentType.LOCALES)
            }
        }

        // Check themes
        remote.content.themes?.let { remoteEntry ->
            if (cached?.content?.themes?.version != remoteEntry.version) {
                updates.add(ContentType.THEMES)
            }
        }

        return updates
    }

    private suspend fun downloadAndCache(
        type: ContentType,
        manifest: ContentManifest
    ) = withContext(Dispatchers.IO) {
        when (type) {
            ContentType.NETWORKS -> {
                val entry = manifest.content.networks
                    ?: throw IllegalStateException("No networks entry")
                val url = URL("${getContentUrl()}${entry.path}")
                downloadAndVerify(url, entry.checksum, type, "networks.json")
            }
            ContentType.LOCALES -> {
                val entry = manifest.content.locales
                    ?: throw IllegalStateException("No locales entry")
                val enFile = entry.files["en"]
                    ?: throw IllegalStateException("No English locale")
                val url = URL("${getContentUrl()}${entry.path}${enFile.path}")
                downloadAndVerify(url, enFile.checksum, type, "en.json")
            }
            ContentType.THEMES -> {
                val entry = manifest.content.themes
                    ?: throw IllegalStateException("No themes entry")
                val url = URL("${getContentUrl()}${entry.path}")
                downloadAndVerify(url, entry.checksum, type, "themes.json")
            }
            ContentType.HELP -> {
                // Not implemented yet
            }
        }
    }

    private suspend fun downloadAndVerify(
        url: URL,
        expectedChecksum: String,
        type: ContentType,
        filename: String
    ) = withContext(Dispatchers.IO) {
        val data = url.readBytes()

        // Verify checksum
        val actualChecksum = computeChecksum(data)
        if (actualChecksum != expectedChecksum) {
            throw SecurityException("Checksum mismatch for $filename")
        }

        // Save to cache
        val cacheDir = File(applicationContext.cacheDir, "vauchi-content/${type.name.lowercase()}")
        cacheDir.mkdirs()

        val file = File(cacheDir, filename)
        val tempFile = File(cacheDir, "$filename.tmp")

        // Atomic write
        tempFile.writeBytes(data)
        tempFile.renameTo(file)

        Log.d(TAG, "Cached $filename (${data.size} bytes)")
    }

    private fun computeChecksum(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(data)
        return "sha256:" + hash.joinToString("") { "%02x".format(it) }
    }
}

/**
 * Content types that can be updated.
 */
enum class ContentType {
    NETWORKS,
    LOCALES,
    THEMES,
    HELP
}

/**
 * Content manifest from remote server.
 */
@Serializable
data class ContentManifest(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("generated_at") val generatedAt: String,
    @SerialName("base_url") val baseUrl: String,
    val content: ContentIndex
)

@Serializable
data class ContentIndex(
    val networks: ContentEntry? = null,
    val locales: LocalesEntry? = null,
    val themes: ContentEntry? = null,
    val help: LocalesEntry? = null
)

@Serializable
data class ContentEntry(
    val version: String,
    val path: String,
    val checksum: String,
    @SerialName("min_app_version") val minAppVersion: String
)

@Serializable
data class LocalesEntry(
    val version: String,
    val path: String,
    @SerialName("min_app_version") val minAppVersion: String,
    val files: Map<String, FileEntry>
)

@Serializable
data class FileEntry(
    val path: String,
    val checksum: String
)
