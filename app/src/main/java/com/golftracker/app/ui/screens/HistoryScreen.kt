package com.golftracker.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.MyLocation
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
import com.golftracker.app.export.ShotHistoryCsv
import com.golftracker.app.location.DeviceOrientation
import com.golftracker.app.location.LocationTrackerManager
import com.golftracker.app.model.ShotRecord
import com.golftracker.app.model.distance
import com.golftracker.app.ui.components.ShotBallLocator
import com.golftracker.app.ui.components.isLocatable
import com.golftracker.app.repository.ShotRepository
import com.golftracker.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    repository: ShotRepository,
    locationManager: LocationTrackerManager,
    onSelectShot: (ShotRecord) -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val shots by repository.shots.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    // The ball locator for a past shot: where you are now and which way the phone points.
    val here by locationManager.currentLocation.collectAsState()
    val orientation = remember { DeviceOrientation(context) }
    DisposableEffect(orientation) {
        orientation.start()
        onDispose { orientation.stop() }
    }
    val heading by orientation.headingDegrees.collectAsState()
    var locating by remember { mutableStateOf<ShotRecord?>(null) }

    // Android's "save as" picker: the user chooses a folder on the phone or in Google Drive.
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val toExport = shots
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(ShotHistoryCsv.build(toExport).toByteArray())
                    } != null
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
            snackbar.showSnackbar(
                if (ok) "Exported ${toExport.size} shot${if (toExport.size == 1) "" else "s"}" else "Couldn't save the export"
            )
        }
    }

    LaunchedEffect(Unit) {
        repository.loadShots()
    }

    val recordings = ShotRepository.recordingCount(shots)
    var isPacking by remember { mutableStateOf(false) }
    // Every shot recording as zips, for the share sheet: to Google Drive for testing and analysis later.
    val shareRecordings: () -> Unit = {
        isPacking = true
        scope.launch {
            val toShare = shots.filter { it.hasRecording }
            val intent = withContext(Dispatchers.IO) { ShotBundle.shareIntent(context, toShare, repository::recordingDir) }
            isPacking = false
            if (intent != null) context.startActivity(intent) else snackbar.showSnackbar("No shot recordings to share")
        }
    }

    val totalShots = shots.size
    // Only shots whose distance is known: pinned on GPS, or estimated from the launch.
    val distances = shots.mapNotNull { it.distance?.yards }
    val maxDistanceYards = distances.maxOrNull()
    val avgDistanceYards = distances.takeIf { it.isNotEmpty() }?.average()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Shot History & Stats", color = Color.White, fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = shareRecordings, enabled = recordings > 0 && !isPacking) {
                        if (isPacking) {
                            CircularProgressIndicator(color = GolfNeonCyan, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                        } else {
                            Icon(
                                Icons.Default.Videocam,
                                contentDescription = "Share all shot recordings",
                                tint = if (recordings > 0) GolfNeonCyan else GolfTextMuted
                            )
                        }
                    }
                    IconButton(
                        onClick = { exportLauncher.launch(ShotHistoryCsv.fileName()) },
                        enabled = shots.isNotEmpty()
                    ) {
                        Icon(
                            Icons.Default.FileDownload,
                            contentDescription = "Export shot history",
                            tint = if (shots.isNotEmpty()) GolfNeonLime else GolfTextMuted
                        )
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
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Overall Stats Summary Card
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = GolfDarkCard),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("TOTAL SHOTS", color = GolfTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text("$totalShots", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    }
                    Column {
                        Text("AVG DISTANCE", color = GolfTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(avgDistanceYards?.let { "%.0f YDS".format(it) } ?: "—", color = GolfNeonLime, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    }
                    Column {
                        Text("LONGEST SHOT", color = GolfTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(maxDistanceYards?.let { "%.0f YDS".format(it) } ?: "—", color = GolfNeonCyan, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    }
                    Column {
                        Text("RECORDINGS", color = GolfTextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "$recordings/${ShotRepository.MAX_RECORDINGS}",
                            color = if (recordings >= ShotRepository.MAX_RECORDINGS) GolfNeonGold else Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (shots.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.SportsGolf,
                            contentDescription = null,
                            tint = GolfTextMuted,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("No shots recorded yet", color = GolfTextSecondary, fontSize = 16.sp)
                        Text("Record shots in the camera tab to trace ball flight", color = GolfTextMuted, fontSize = 13.sp)
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(shots, key = { it.id }) { shot ->
                        ShotHistoryCard(
                            shot = shot,
                            onClick = { onSelectShot(shot) },
                            onLocate = { locating = shot },
                            onDelete = {
                                scope.launch { repository.deleteShot(shot.id) }
                            }
                        )
                    }
                }
            }
        }
    }

    locating?.let { shot -> ShotBallLocator(shot, here, heading) { locating = null } }
}

@Composable
fun ShotHistoryCard(
    shot: ShotRecord,
    onClick: () -> Unit,
    onLocate: () -> Unit,
    onDelete: () -> Unit
) {
    val sdf = SimpleDateFormat("MMM dd, yyyy • HH:mm", Locale.US)
    val dateStr = sdf.format(Date(shot.timestampMs))

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = GolfDarkCard),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail Image
            Box(
                modifier = Modifier
                    .size(70.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(GolfDarkSurface),
                contentAlignment = Alignment.Center
            ) {
                if (shot.snapshotUri != null) {
                    AsyncImage(
                        model = shot.snapshotUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(Icons.Default.SportsGolf, contentDescription = null, tint = GolfNeonLime)
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(GolfNeonLime.copy(alpha = 0.2f))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(shot.club.uppercase(), color = GolfNeonLime, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    shot.golfer?.let { golfer ->
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(GolfNeonCyan.copy(alpha = 0.2f))
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("GOLFER $golfer", color = GolfNeonCyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(dateStr, color = GolfTextMuted, fontSize = 11.sp)
                    if (shot.hasRecording) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(Icons.Default.Videocam, contentDescription = "Has recording", tint = GolfNeonCyan, modifier = Modifier.size(14.dp))
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(verticalAlignment = Alignment.Bottom) {
                    val distance = shot.distance
                    Text(distance?.label ?: "No distance", color = if (distance != null) Color.White else GolfTextMuted, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    distance?.let {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (it.estimated) "estimated" else "GPS", color = GolfTextSecondary, fontSize = 12.sp)
                    }
                }
            }

            // The ball locator, for a shot with a landing estimate or a pinned ball position.
            val locatable = shot.isLocatable
            IconButton(onClick = onLocate, enabled = locatable) {
                Icon(
                    Icons.Default.MyLocation,
                    contentDescription = "Locate ball",
                    tint = if (locatable) GolfNeonCyan else GolfTextMuted.copy(alpha = 0.4f)
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = GolfTextMuted)
            }
        }
    }
}
