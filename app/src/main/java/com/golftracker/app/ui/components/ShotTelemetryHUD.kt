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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.golftracker.app.model.ShotRecord
import com.golftracker.app.ui.theme.*

/** One compact row: brand (or shot distance once revealed), golfer and club selectors, and the two GPS buttons. */
@Composable
fun ShotTelemetryHUD(
    distanceYards: Double,
    showShotDistance: Boolean,
    clubName: String,
    /** Which golfer the next shot is tagged with, 1 to [ShotRecord.MAX_GOLFERS]. */
    golfer: Int,
    onGolferChange: (Int) -> Unit,
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

            // Golfer selector: a short menu of golfers 1 to 4
            var choosingGolfer by remember { mutableStateOf(false) }
            Box {
                Selection(text = "GOLFER $golfer", color = GolfNeonCyan, onClick = { choosingGolfer = true })
                DropdownMenu(
                    expanded = choosingGolfer,
                    onDismissRequest = { choosingGolfer = false },
                    containerColor = GolfDarkCard
                ) {
                    for (n in 1..ShotRecord.MAX_GOLFERS) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Golfer $n",
                                    color = if (n == golfer) GolfNeonCyan else Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = if (n == golfer) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            onClick = {
                                onGolferChange(n)
                                choosingGolfer = false
                            }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(6.dp))

            // Club selector
            Selection(text = clubName.uppercase(), color = GolfNeonLime, onClick = onClubClick)

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

/** A tappable choice on the card, such as the club or the golfer. */
@Composable
private fun Selection(text: String, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.15f))
            .clickable { onClick() }
            .padding(horizontal = 9.dp, vertical = 5.dp)
    ) {
        Text(text = text, color = color, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
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
