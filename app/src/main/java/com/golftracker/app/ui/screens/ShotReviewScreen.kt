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
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SportsGolf
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.golftracker.app.export.ShotBundle
import com.golftracker.app.export.ShotVideoExporter
import com.golftracker.app.location.DeviceOrientation
import com.golftracker.app.location.LocationTrackerManager
import com.golftracker.app.model.ShotRecord
import com.golftracker.app.model.distance
import com.golftracker.app.ui.components.ShotBallLocator
import com.golftracker.app.ui.components.isLocatable
import com.golftracker.app.model.TracerStyle
import com.golftracker.app.repository.ShotRepository
import com.golftracker.app.ui.components.ShotVideoPlayer
import com.golftracker.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShotReviewScreen(
    shotRecord: ShotRecord,
    repository: ShotRepository,
    locationManager: LocationTrackerManager,
    onBack: () -> Unit,
    onRecordNext: () -> Unit
) {
    val context = LocalContext.current
    // The ball locator for this shot: where you are now and which way the phone points.
    val here by locationManager.currentLocation.collectAsState()
    val orientation = remember { DeviceOrientation(context) }
    DisposableEffect(orientation) {
        orientation.start()
        onDispose { orientation.stop() }
    }
    val heading by orientation.headingDegrees.collectAsState()
    var locating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // The shot keeps changing for a while after it's taken: traced again from its video, then its snapshot baked.
    val shots by repository.shots.collectAsState()
    val shotRecord = shots.firstOrNull { it.id == shotRecord.id } ?: shotRecord
    val videoPath = shotRecord.videoPath?.takeIf { File(it).exists() }
    var showSnapshot by remember { mutableStateOf(false) }
    var isPacking by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    // Exporting the video with the tracer drawn in: how far along, or null when not exporting.
    var exportProgress by remember { mutableStateOf<Float?>(null) }
    val exportVideo: () -> Unit = {
        exportProgress = 0f
        scope.launch {
            val outcome = ShotVideoExporter.export(context, listOf(shotRecord)) { exportProgress = it }
            exportProgress = null
            val shared = snackbar.showSnackbar(
                if (outcome.saved.isEmpty()) "Couldn't make the video" else outcome.summary,
                actionLabel = if (outcome.saved.isEmpty()) null else "Share",
                duration = SnackbarDuration.Long
            )
            if (shared == SnackbarResult.ActionPerformed) context.startActivity(ShotVideoExporter.shareIntent(outcome.saved))
        }
    }

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
                    if (videoPath != null) {
                        // The video with the tracer drawn in, saved to Movies/GolfTracker to post or send.
                        IconButton(enabled = exportProgress == null, onClick = exportVideo) {
                            val progress = exportProgress
                            if (progress != null) {
                                CircularProgressIndicator(progress = { progress }, color = GolfNeonLime, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                            } else {
                                Icon(Icons.Default.Movie, contentDescription = "Export video with tracer", tint = GolfNeonLime)
                            }
                        }
                    }
                    if (shotRecord.hasRecording) {
                        // The recording and tracking data as one zip, e.g. to Google Drive for analysis later.
                        IconButton(enabled = !isPacking, onClick = {
                            isPacking = true
                            scope.launch {
                                val intent = withContext(Dispatchers.IO) {
                                    ShotBundle.shareIntent(context, listOf(shotRecord), repository::recordingDir)
                                }
                                isPacking = false
                                intent?.let { context.startActivity(it) }
                            }
                        }) {
                            if (isPacking) {
                                CircularProgressIndicator(color = GolfNeonCyan, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                            } else {
                                Icon(Icons.Default.Videocam, contentDescription = "Share recording", tint = GolfNeonCyan)
                            }
                        }
                    }
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
        snackbarHost = { SnackbarHost(snackbar) },
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
                    .height(if (videoPath != null && !showSnapshot) 460.dp else 380.dp)
            ) {
                if (videoPath != null && !showSnapshot) {
                    ShotVideoPlayer(
                        videoPath = videoPath,
                        points = shotRecord.points,
                        style = runCatching { TracerStyle.valueOf(shotRecord.tracerStyle) }.getOrDefault(TracerStyle.PRO_LIME),
                        videoStartMs = shotRecord.videoStartMs,
                        frameAspect = shotRecord.frameAspect ?: (9f / 16f),
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (shotRecord.snapshotUri != null) {
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

            if (videoPath != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        when (shotRecord.tracedFrom) {
                            ShotRecord.TRACED_FROM_VIDEO -> "Tracer from the full video"
                            else -> "Tracer from live camera"
                        } + (if (shotRecord.tracerEstimated) " • dashed part estimated" else "") + " • tap video to pause",
                        color = GolfTextSecondary,
                        fontSize = 12.sp
                    )
                    if (shotRecord.snapshotUri != null) {
                        TextButton(onClick = { showSnapshot = !showSnapshot }) {
                            Text(if (showSnapshot) "Show video" else "Show snapshot", color = GolfNeonCyan, fontSize = 12.sp)
                        }
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
                            val distance = shotRecord.distance
                            Text("Total Distance", color = GolfTextSecondary, fontSize = 12.sp)
                            Text(
                                distance?.label ?: "—",
                                color = Color.White,
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Black
                            )
                            Text(
                                when {
                                    distance == null -> "Pin the ball's GPS to measure it"
                                    distance.estimated -> "Estimated from the launch"
                                    else -> "Measured on GPS"
                                },
                                color = GolfTextMuted,
                                fontSize = 13.sp
                            )
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
                                    shotRecord.club.uppercase() + (shotRecord.golfer?.let { " • GOLFER $it" } ?: ""),
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

            // The ball locator for this shot, from wherever you are now.
            OutlinedButton(
                onClick = { locating = true },
                enabled = shotRecord.isLocatable,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                Icon(
                    Icons.Default.MyLocation,
                    contentDescription = null,
                    tint = if (shotRecord.isLocatable) GolfNeonCyan else GolfTextMuted,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (shotRecord.isLocatable) "Locate Ball" else "No landing spot to locate",
                    color = if (shotRecord.isLocatable) GolfNeonCyan else GolfTextMuted,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
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

    if (locating) ShotBallLocator(shotRecord, here, heading) { locating = false }
}
