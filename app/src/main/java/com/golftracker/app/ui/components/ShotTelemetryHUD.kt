package com.golftracker.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.SportsGolf
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.golftracker.app.model.GpsCoordinate
import com.golftracker.app.ui.theme.*

/** One compact row: brand (or shot distance once revealed), club selector and the two GPS buttons. */
@Composable
fun ShotTelemetryHUD(
    distanceYards: Double,
    distanceMeters: Double,
    showShotDistance: Boolean,
    clubName: String,
    gpsLaunch: GpsCoordinate?,
    gpsLanding: GpsCoordinate?,
    onClubClick: () -> Unit,
    onLockTeeClick: () -> Unit,
    onLockLandingClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = GolfDarkCard.copy(alpha = 0.85f)),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.border(1.dp, GolfNeonLime.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .padding(start = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Brand, or the shot distance once it has been revealed
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                if (showShotDistance && distanceYards > 0.0) {
                    Text(
                        text = "%.0f YDS".format(distanceYards),
                        color = GolfNeonLime,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = " (%.0f m)".format(distanceMeters),
                        color = GolfTextMuted,
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.SportsGolf,
                        contentDescription = null,
                        tint = GolfNeonLime,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "GolfBallTracer",
                        color = GolfNeonLime,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Club selector
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(GolfNeonLime.copy(alpha = 0.15f))
                    .clickable { onClubClick() }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = clubName.uppercase(),
                    color = GolfNeonLime,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1
                )
            }

            GpsButton(
                icon = if (gpsLaunch != null) Icons.Default.GpsFixed else Icons.Default.GpsOff,
                description = if (gpsLaunch != null) "Tee GPS locked" else "Set tee GPS",
                active = gpsLaunch != null,
                activeColor = GolfNeonLime,
                onClick = onLockTeeClick
            )
            GpsButton(
                icon = Icons.Default.Navigation,
                description = if (gpsLanding != null) "Ball GPS pinned" else "Pin ball GPS",
                active = gpsLanding != null,
                activeColor = GolfNeonCyan,
                onClick = onLockLandingClick
            )
        }
    }
}

@Composable
private fun GpsButton(icon: ImageVector, description: String, active: Boolean, activeColor: Color, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (active) activeColor else GolfDarkSurface),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                tint = if (active) GolfDarkBg else activeColor,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
