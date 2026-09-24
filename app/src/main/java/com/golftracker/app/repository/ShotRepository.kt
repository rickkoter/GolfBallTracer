package com.golftracker.app.repository

import android.content.Context
import com.golftracker.app.model.ShotRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _shots = MutableStateFlow<List<ShotRecord>>(emptyList())
    val shots: StateFlow<List<ShotRecord>> = _shots.asStateFlow()

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

    suspend fun saveShot(shot: ShotRecord) = withContext(Dispatchers.IO) {
        try {
            val file = File(storageDir, "${shot.id}.json")
            val text = json.encodeToString(shot)
            file.writeText(text)
            loadShots()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun deleteShot(shotId: String) = withContext(Dispatchers.IO) {
        try {
            val file = File(storageDir, "$shotId.json")
            if (file.exists()) file.delete()
            loadShots()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
