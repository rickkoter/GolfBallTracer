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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.golftracker.app.model.GpsCoordinate
import com.golftracker.app.ui.theme.*

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
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, GolfNeonLime.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Header Row: Brand + Optional Side-by-Side Distance + Club Selector Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: Brand or Side-by-Side Distance Label & Yardage
                if (showShotDistance && distanceYards > 0.0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "SHOT DISTANCE: ",
                            color = GolfTextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            text = "%.0f YDS".format(distanceYards),
                            color = GolfNeonLime,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = " (%.0f m)".format(distanceMeters),
                            color = GolfTextMuted,
                            fontSize = 10.sp
                        )
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.SportsGolf,
                            contentDescription = null,
                            tint = GolfNeonLime,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "GOLFBALLTRACER",
                            color = GolfNeonLime,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    }
                }

                // Right: Compact Club Selector Badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(GolfNeonLime.copy(alpha = 0.15f))
                        .clickable { onClubClick() }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = clubName.uppercase(),
                        color = GolfNeonLime,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            // Compact GPS Actions Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Tee Lock Button
                OutlinedButton(
                    onClick = onLockTeeClick,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (gpsLaunch != null) GolfNeonLime else GolfTextSecondary
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(32.dp)
                ) {
                    Icon(
                        imageVector = if (gpsLaunch != null) Icons.Default.GpsFixed else Icons.Default.GpsOff,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (gpsLaunch != null) "Tee Locked" else "Set Tee GPS",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Ball Pin Lock Button
                Button(
                    onClick = onLockLandingClick,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (gpsLanding != null) GolfNeonCyan else GolfDarkSurface
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Navigation,
                        contentDescription = null,
                        tint = if (gpsLanding != null) GolfDarkBg else GolfNeonCyan,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (gpsLanding != null) "Ball Pinned" else "Pin Ball GPS",
                        color = if (gpsLanding != null) GolfDarkBg else GolfNeonCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
