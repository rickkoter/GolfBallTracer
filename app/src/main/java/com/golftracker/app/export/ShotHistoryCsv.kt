package com.golftracker.app.export

import com.golftracker.app.model.ShotRecord
import com.golftracker.app.model.TracerStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Shot history as CSV, one row per shot, for Google Sheets or Excel. */
object ShotHistoryCsv {

    private val HEADER = listOf(
        "Date", "Time", "Club", "Distance (yd)", "Distance (m)",
        "Tee latitude", "Tee longitude", "Landing latitude", "Landing longitude",
        "Tracer style", "Notes", "Shot ID"
    )

    fun build(shots: List<ShotRecord>, timeZone: TimeZone = TimeZone.getDefault()): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { this.timeZone = timeZone }
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).apply { this.timeZone = timeZone }
        val rows = shots.sortedBy { it.timestampMs }.map { shot ->
            val at = Date(shot.timestampMs)
            listOf(
                date.format(at),
                time.format(at),
                shot.club,
                if (shot.distanceYards > 0) "%.1f".format(Locale.US, shot.distanceYards) else "",
                if (shot.distanceMeters > 0) "%.1f".format(Locale.US, shot.distanceMeters) else "",
                shot.launchLocation?.latitude?.let { "%.6f".format(Locale.US, it) } ?: "",
                shot.launchLocation?.longitude?.let { "%.6f".format(Locale.US, it) } ?: "",
                shot.landingLocation?.latitude?.let { "%.6f".format(Locale.US, it) } ?: "",
                shot.landingLocation?.longitude?.let { "%.6f".format(Locale.US, it) } ?: "",
                TracerStyle.entries.firstOrNull { it.name == shot.tracerStyle }?.displayName ?: shot.tracerStyle,
                shot.notes,
                shot.id
            )
        }
        return (listOf(HEADER) + rows).joinToString("\r\n", postfix = "\r\n") { row -> row.joinToString(",") { escape(it) } }
    }

    /** Quotes a field when it holds a comma, quote or line break, doubling any quotes inside. */
    private fun escape(field: String): String =
        if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + field.replace("\"", "\"\"") + "\"" else field

    fun fileName(now: Date = Date()): String =
        "GolfBallTracer-shots-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now) + ".csv"
}
