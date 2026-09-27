package com.golftracker.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SportsGolf
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.golftracker.app.model.ShotRecord
import com.golftracker.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShotReviewScreen(
    shotRecord: ShotRecord,
    onBack: () -> Unit,
    onRecordNext: () -> Unit
) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Shot Review", color = Color.White, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val uriString = shotRecord.snapshotUri ?: return@IconButton
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "image/jpeg"
                            putExtra(Intent.EXTRA_STREAM, Uri.parse(uriString))
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share Golf Shot Tracer"))
                    }) {
                        Icon(Icons.Default.Share, contentDescription = "Share", tint = GolfNeonLime)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = GolfDarkBg)
            )
        },
        containerColor = GolfDarkBg
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // Snapshot Image Frame
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = GolfDarkCard),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(380.dp)
            ) {
                if (shotRecord.snapshotUri != null) {
                    AsyncImage(
                        model = shotRecord.snapshotUri,
                        contentDescription = "Shot Tracer Snapshot",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.SportsGolf,
                            contentDescription = null,
                            tint = GolfNeonLime,
                            modifier = Modifier.size(64.dp)
                        )
                    }
                }
            }

            // Shot Metrics Card
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = GolfDarkCard),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "SHOT TELEMETRY",
                        color = GolfNeonLime,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Total Distance", color = GolfTextSecondary, fontSize = 12.sp)
                            Text(
                                "%.0f YDS".format(shotRecord.distanceYards),
                                color = Color.White,
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Black
                            )
                            Text("(%.0f m)".format(shotRecord.distanceMeters), color = GolfTextMuted, fontSize = 13.sp)
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text("Club", color = GolfTextSecondary, fontSize = 12.sp)
                            Box(
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(GolfNeonLime.copy(alpha = 0.15f))
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    shotRecord.club.uppercase(),
                                    color = GolfNeonLime,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = Color.White.copy(alpha = 0.1f))

                    // GPS Coordinates Section
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Tee GPS Location", color = GolfTextSecondary, fontSize = 12.sp)
                            Text(
                                shotRecord.launchLocation?.let { "%.4f, %.4f".format(it.latitude, it.longitude) } ?: "N/A",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text("Landing GPS Pin", color = GolfTextSecondary, fontSize = 12.sp)
                            Text(
                                shotRecord.landingLocation?.let { "%.4f, %.4f".format(it.latitude, it.longitude) } ?: "Estimated",
                                color = GolfNeonCyan,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            Button(
                onClick = onRecordNext,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = GolfNeonLime),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                Text("Record Next Shot", color = GolfDarkBg, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
