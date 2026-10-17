package com.golftracker.app.repository

import android.content.Context
import com.golftracker.app.model.ShotRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class ShotRepository(private val context: Context) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val storageDir: File by lazy {
        File(context.filesDir, "shot_data").apply { if (!exists()) mkdirs() }
    }

    private val recordingsDir: File by lazy { File(context.filesDir, RECORDINGS_FOLDER) }

    private val _shots = MutableStateFlow<List<ShotRecord>>(emptyList())
    val shots: StateFlow<List<ShotRecord>> = _shots.asStateFlow()

    /** Shots are updated from the background while a recording is processed, and from the screens. */
    private val writeLock = Mutex()

    /** Where a shot's video and tracking data are kept. */
    fun recordingDir(shotId: String): File = File(recordingsDir, shotId)

    suspend fun loadShots() = withContext(Dispatchers.IO) {
        val shotFiles = storageDir.listFiles { _, name -> name.endsWith(".json") } ?: emptyArray()
        val loaded = shotFiles.mapNotNull { file ->
            try {
                val text = file.readText()
                json.decodeFromString<ShotRecord>(text)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }.sortedByDescending { it.timestampMs }

        _shots.value = loaded
    }

    suspend fun find(shotId: String): ShotRecord? = withContext(Dispatchers.IO) {
        try {
            File(storageDir, "$shotId.json").takeIf { it.exists() }?.let { json.decodeFromString<ShotRecord>(it.readText()) }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun saveShot(shot: ShotRecord) = withContext(Dispatchers.IO) {
        writeLock.withLock { write(shot) }
        loadShots()
    }

    /** Changes a saved shot in place; returns it as saved, or null if there's no such shot. */
    suspend fun update(shotId: String, change: (ShotRecord) -> ShotRecord): ShotRecord? = withContext(Dispatchers.IO) {
        val updated = writeLock.withLock {
            val current = find(shotId) ?: return@withLock null
            change(current).also { write(it) }
        }
        loadShots()
        updated
    }

    private fun write(shot: ShotRecord) {
        try {
            File(storageDir, "${shot.id}.json").writeText(json.encodeToString(shot))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun deleteShot(shotId: String) = withContext(Dispatchers.IO) {
        try {
            writeLock.withLock {
                val file = File(storageDir, "$shotId.json")
                if (file.exists()) file.delete()
                recordingDir(shotId).deleteRecursively()
            }
            loadShots()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Deletes the recordings of these shots, keeping the shots themselves. */
    suspend fun deleteRecordings(shotIds: Collection<String>) = withContext(Dispatchers.IO) {
        for (id in shotIds) {
            recordingDir(id).deleteRecursively()
            update(id) { it.copy(videoPath = null, trackingPath = null, videoStartMs = null) }
        }
        loadShots()
    }

    companion object {
        const val RECORDINGS_FOLDER = "shot_media"

        /** Most shots whose recordings are kept; no more are recorded until some are removed. */
        const val MAX_RECORDINGS = 32

        /** How many of the earliest recordings to offer to remove once the limit is reached. */
        const val RECORDINGS_TO_FREE = 18

        fun recordingCount(shots: List<ShotRecord>): Int = shots.count { it.hasRecording }

        /** The [n] earliest shots that still have a recording. */
        fun earliestRecordings(shots: List<ShotRecord>, n: Int): List<ShotRecord> =
            shots.filter { it.hasRecording }.sortedBy { it.timestampMs }.take(n)
    }
}
