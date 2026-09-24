package com.golftracker.app.model

import kotlinx.serialization.Serializable
import java.util.UUID

enum class ClubType(val displayName: String, val category: String) {
    DRIVER("Driver", "Wood"),
    WOOD_3("3-Wood", "Wood"),
    HYBRID_3("3-Hybrid", "Hybrid"),
    IRON_4("4-Iron", "Iron"),
    IRON_5("5-Iron", "Iron"),
    IRON_6("6-Iron", "Iron"),
    IRON_7("7-Iron", "Iron"),
    IRON_8("8-Iron", "Iron"),
    IRON_9("9-Iron", "Iron"),
    PITCHING_WEDGE("PW", "Wedge"),
    GAP_WEDGE("GW", "Wedge"),
    SAND_WEDGE("SW", "Wedge"),
    LOB_WEDGE("LW", "Wedge"),
    PUTTER("Putter", "Putter")
}

enum class TracerStyle(val displayName: String, val colorHex: Long, val secondaryHex: Long) {
    PRO_LIME("Pro Lime", 0xFF00FF66, 0x8800E676),
    CYAN_SPARK("Cyan Spark", 0xFF00E5FF, 0x8800B0FF),
    FIRE_RED("Fire Red", 0xFFFF3D00, 0x88FF9100),
    GOLD_GLOW("Gold Glow", 0xFFFFD700, 0x88FFAB00),
    NEON_PURPLE("Neon Purple", 0xFFE040FB, 0x88D500F9)
}

@Serializable
data class GpsCoordinate(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double = 0.0,
    val accuracyMeters: Float = 0f
)

@Serializable
data class ScreenPoint(
    val x: Float,
    val y: Float,
    val timestampMs: Long = System.currentTimeMillis(),
    val confidence: Float = 1.0f
)

@Serializable
data class ShotRecord(
    val id: String = UUID.randomUUID().toString(),
    val timestampMs: Long = System.currentTimeMillis(),
    val club: String = ClubType.DRIVER.displayName,
    val tracerStyle: String = TracerStyle.PRO_LIME.name,
    
    // GPS Telemetry
    val launchLocation: GpsCoordinate? = null,
    val landingLocation: GpsCoordinate? = null,
    val distanceYards: Double = 0.0,
    val distanceMeters: Double = 0.0,
    val elevationDeltaMeters: Double = 0.0,
    
    // Optical & Ballistic Telemetry
    val launchAngleDegrees: Float = 14.5f,
    val estimatedApexYards: Float = 32f,
    val flightTimeSeconds: Float = 5.2f,
    
    // Screen Trajectory Points
    val points: List<ScreenPoint> = emptyList(),
    
    // Media & Assets
    val snapshotUri: String? = null,
    val notes: String = ""
)
