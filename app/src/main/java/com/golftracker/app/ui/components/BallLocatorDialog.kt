package com.golftracker.app.ui.components

import android.hardware.GeomagneticField
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.golftracker.app.model.GpsCoordinate
import com.golftracker.app.model.ShotRecord
import com.golftracker.app.tracker.LaunchPhysics
import com.golftracker.app.tracker.TrajectoryMath
import com.golftracker.app.ui.theme.*

private const val METERS_PER_YARD = 0.9144

/** Color for how close you are: green within 3 yards, yellow within 15, red further away. */
fun locatorDistanceColor(yards: Double): Color = when {
    yards <= 3.0 -> Color(0xFF00E676)
    yards <= 15.0 -> Color(0xFFFFD600)
    else -> Color(0xFFFF3D00)
}

/**
 * Points the way to where the ball is estimated to have landed: distance from here (colored by how
 * close), an arrow that turns with the phone, the compass bearing, and the landing coordinates.
 *
 * @param landing the estimate, or null with [unavailableReason] saying why there isn't one
 * @param here the phone's current location
 * @param headingDegrees where the phone is pointing, from magnetic north
 */
@Composable
fun BallLocatorDialog(
    landing: LaunchPhysics.Landing?,
    unavailableReason: String?,
    here: GpsCoordinate?,
    headingDegrees: Float?,
    /** [landing] is where the ball was pinned on GPS, rather than an estimate from the video. */
    pinned: Boolean = false,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            color = GolfDarkBg,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("Ball Locator", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)

                when {
                    landing == null -> Text(
                        unavailableReason ?: "No landing estimate for this shot.",
                        color = GolfTextSecondary, fontSize = 15.sp, textAlign = TextAlign.Center
                    )
                    here == null -> Text("Waiting for GPS…", color = GolfTextSecondary, fontSize = 15.sp)
                    else -> LocatorBody(landing, here, headingDegrees)
                }

                if (pinned) landing?.let {
                    Text(
                        "Ball pinned at %.6f, %.6f".format(it.position.latitude, it.position.longitude),
                        color = GolfTextSecondary, fontSize = 12.sp, textAlign = TextAlign.Center
                    )
                } else landing?.let {
                    Text(
                        "Landing (estimated): %.6f, %.6f".format(it.position.latitude, it.position.longitude),
                        color = GolfTextSecondary, fontSize = 12.sp, textAlign = TextAlign.Center
                    )
                    Text(
                        "About %.0f yd carry, %.0f yd with roll · left at %.0f mph, %.0f° up".format(
                            it.carryMeters / 0.9144, it.totalMeters / 0.9144, it.launchSpeedMps * 2.23694, it.launchAngleDegrees
                        ),
                        color = GolfTextMuted, fontSize = 12.sp, textAlign = TextAlign.Center
                    )
                    Text(
                        "A rough estimate from the video; look around this spot.",
                        color = GolfTextMuted, fontSize = 11.sp, textAlign = TextAlign.Center
                    )
                }

                TextButton(onClick = onDismiss) { Text("Close", color = GolfNeonLime, fontSize = 16.sp) }
            }
        }
    }
}

/**
 * The ball locator for a saved shot: its landing estimate, or else where its ball was pinned on GPS.
 * Used from the shot log and the shot's review.
 */
@Composable
fun ShotBallLocator(shot: ShotRecord, here: GpsCoordinate?, heading: Float?, onDismiss: () -> Unit) {
    val estimate = shot.estimatedLanding
    val pinned = shot.landingLocation
    val landing = estimate ?: pinned?.let { ball ->
        val tee = shot.launchLocation
        val meters = tee?.let { TrajectoryMath.calculateGpsDistanceMeters(it, ball) } ?: 0.0
        LaunchPhysics.Landing(
            position = ball,
            bearingDegrees = tee?.let { LaunchPhysics.bearing(it, ball) } ?: 0.0,
            carryMeters = meters,
            totalMeters = meters,
            launchSpeedMps = 0.0,
            launchAngleDegrees = 0.0
        )
    }
    BallLocatorDialog(
        landing = landing,
        unavailableReason = "This shot has no landing estimate or pinned ball position.",
        here = here,
        headingDegrees = heading,
        pinned = estimate == null && pinned != null,
        onDismiss = onDismiss
    )
}

/** Whether [ShotBallLocator] has somewhere to point to for this shot. */
val ShotRecord.isLocatable: Boolean get() = estimatedLanding != null || landingLocation != null

@Composable
private fun LocatorBody(landing: LaunchPhysics.Landing, here: GpsCoordinate, headingDegrees: Float?) {
    val meters = TrajectoryMath.calculateGpsDistanceMeters(here, landing.position)
    val yards = meters / METERS_PER_YARD
    val bearing = LaunchPhysics.bearing(here, landing.position)
    val color = locatorDistanceColor(yards)
    // The compass reads magnetic north; bearings are from true north.
    val declination = remember(here.latitude.toInt(), here.longitude.toInt()) {
        GeomagneticField(here.latitude.toFloat(), here.longitude.toFloat(), here.altitude.toFloat(), System.currentTimeMillis()).declination
    }

    // Arrow turned by how far the ball is from where the phone points.
    Box(
        modifier = Modifier
            .size(180.dp)
            .clip(RoundedCornerShape(90.dp))
            .background(GolfDarkCard),
        contentAlignment = Alignment.Center
    ) {
        if (headingDegrees == null) {
            Text("No compass", color = GolfTextMuted, fontSize = 13.sp)
        } else {
            val turn = (bearing - (headingDegrees + declination)).toFloat()
            Canvas(modifier = Modifier.size(140.dp).rotate(turn)) {
                val w = size.width
                val h = size.height
                val arrow = Path().apply {
                    moveTo(w / 2, 0f)
                    lineTo(w * 0.85f, h * 0.55f)
                    lineTo(w * 0.6f, h * 0.55f)
                    lineTo(w * 0.6f, h)
                    lineTo(w * 0.4f, h)
                    lineTo(w * 0.4f, h * 0.55f)
                    lineTo(w * 0.15f, h * 0.55f)
                    close()
                }
                drawPath(arrow, color)
                drawCircle(Color.White.copy(alpha = 0.3f), radius = w / 2, center = Offset(w / 2, h / 2), style = Stroke(width = 2f))
            }
        }
    }

    Text(
        // Close in, to the yard and a bit: the last few yards are what finding the ball comes down to.
        text = if (yards < 10) "%.1f yd".format(yards) else "%.0f yd".format(yards),
        color = color,
        fontSize = 44.sp,
        fontWeight = FontWeight.ExtraBold
    )
    Text(
        text = when {
            yards <= 3.0 -> "You're there. Look around your feet."
            else -> "Head %s · %.0f°".format(compassPoint(bearing), bearing)
        },
        color = Color.White,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold
    )
    if (here.accuracyMeters > 0f) {
        Text("GPS ±%.0f yd".format(here.accuracyMeters / METERS_PER_YARD), color = GolfTextMuted, fontSize = 12.sp)
    }
}

private fun compassPoint(bearing: Double): String {
    val points = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return points[(((bearing + 22.5) % 360) / 45).toInt()]
}
