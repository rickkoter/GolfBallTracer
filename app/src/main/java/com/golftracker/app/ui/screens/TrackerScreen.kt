package com.golftracker.app.ui.screens

import android.graphics.Bitmap
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.golftracker.app.export.ShotDebugExporter
import com.golftracker.app.export.SnapshotExporter
import com.golftracker.app.location.LocationTrackerManager
import com.golftracker.app.model.*
import com.golftracker.app.repository.ShotRepository
import com.golftracker.app.tracker.BallFlightFitter
import com.golftracker.app.tracker.ShotCaptureSession
import com.golftracker.app.tracker.TrajectoryMath
import com.golftracker.app.ui.components.BallTracerCanvas
import com.golftracker.app.ui.components.CameraPreviewView
import com.golftracker.app.ui.components.ShotTelemetryHUD
import com.golftracker.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackerScreen(
    locationManager: LocationTrackerManager,
    repository: ShotRepository,
    onNavigateToReview: (ShotRecord) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isRecording by remember { mutableStateOf(false) }
    var isFlashEnabled by remember { mutableStateOf(false) }
    var isApexEditMode by remember { mutableStateOf(false) }
    var isShotRevealed by remember { mutableStateOf(false) }
    var isBallLaunched by remember { mutableStateOf(false) }
    var isAnalyzing by remember { mutableStateOf(false) }
    var isBallFlightMissing by remember { mutableStateOf(false) }
    // Diagnostics from the last shot: what the detector saw, shown when no flight is found.
    var shotDiagnostics by remember { mutableStateOf<String?>(null) }
    var detectedSpots by remember { mutableStateOf<List<ScreenPoint>>(emptyList()) }

    var selectedClub by remember { mutableStateOf(ClubType.DRIVER.displayName) }
    var selectedTracerStyle by remember { mutableStateOf(TracerStyle.PRO_LIME) }
    var detectedPoints by remember { mutableStateOf<List<ScreenPoint>>(emptyList()) }
    var tappedBallLocation by remember { mutableStateOf<ScreenPoint?>(null) }

    var showClubSheet by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    val currentLocation by locationManager.currentLocation.collectAsState()
    val gpsLaunch by locationManager.launchLocation.collectAsState()
    val gpsLanding by locationManager.landingLocation.collectAsState()
    val distanceYards by locationManager.shotDistanceYards.collectAsState()
    val distanceMeters by locationManager.shotDistanceMeters.collectAsState()

    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }

    // The finished shot is shown on a still from when the ball took off rather than on the live camera,
    // so the tracer sits over the swing. Until launch is seen, the still from the tap stands in.
    var tapStill by remember { mutableStateOf<Bitmap?>(null) }
    var shotStill by remember { mutableStateOf<Bitmap?>(null) }
    // The ball found at the tap (sizes the marker), and whether the last tap missed the ball.
    var teeBallRadius by remember { mutableStateOf<Float?>(null) }
    var tapMissedBall by remember { mutableStateOf(false) }
    var launchSeen by remember { mutableStateOf(false) }
    // How far the camera had moved when the still was taken; the tracer is shifted by it to line up.
    var stillShift by remember { mutableStateOf(0f to 0f) }

    // Collects everything that moves while recording; the ball is picked out after the shot.
    val captureSession = remember {
        ShotCaptureSession(keepFrames = true, onLaunchDetected = { launched ->
            isBallLaunched = launched
            val shift = cameraShift()
            // Called on the camera thread; grab the preview on the main thread.
            scope.launch {
                if (launched) {
                    previewViewRef?.bitmap?.let {
                        shotStill = it
                        stillShift = shift
                    }
                } else {
                    shotStill = tapStill
                    stillShift = 0f to 0f
                }
            }
        }, onTeeMeasured = { ball ->
            scope.launch {
                if (!isRecording) return@launch
                if (ball != null) {
                    // Snap the marker onto the ball that was found.
                    tappedBallLocation = ScreenPoint(ball.x, ball.y)
                    teeBallRadius = ball.radius
                } else {
                    // Nothing ball-like where the user tapped: without it the launch can't be seen,
                    // so stop and ask for another tap rather than record a shot that can't work.
                    reset()
                    isRecording = false
                    tappedBallLocation = null
                    tapStill = null
                    shotStill = null
                    tapMissedBall = true
                }
            }
        })
    }

    fun List<ScreenPoint>.shiftedToStill(): List<ScreenPoint> {
        val (dx, dy) = stillShift
        return if (dx == 0f && dy == 0f) this else map { it.copy(x = it.x + dx, y = it.y + dy) }
    }

    fun resetShot() {
        captureSession.reset()
        tappedBallLocation = null
        isRecording = false
        detectedPoints = emptyList()
        isShotRevealed = false
        isBallLaunched = false
        isBallFlightMissing = false
        shotDiagnostics = null
        detectedSpots = emptyList()
        tapStill = null
        shotStill = null
        stillShift = 0f to 0f
        teeBallRadius = null
        tapMissedBall = false
    }

    // Stops recording and finds the ball's flight among everything that moved.
    suspend fun analyzeRecordedShot() {
        isRecording = false
        val shot = captureSession.stop()
        isAnalyzing = true
        val flight = withContext(Dispatchers.Default) { BallFlightFitter.fit(shot) }
        detectedPoints = flight.shiftedToStill()
        isBallFlightMissing = flight.isEmpty()
        launchSeen = shot.launchTimestampNs != null
        isAnalyzing = false

        val allFrames = shot.firstFrames + shot.recentFrames
        val seconds = if (allFrames.size > 1) (allFrames.last().timestampNs - allFrames.first().timestampNs) / 1e9 else 0.0
        val fps = if (seconds > 0) shot.framesProcessed / seconds else 0.0
        val stats = "%d spots • %.1f s at %.0f fps • ball on tee %s • launch %s".format(
            shot.candidates.size, seconds, fps,
            if (shot.ballRadius != null) "✓" else "✗",
            if (shot.launchTimestampNs != null) "✓" else "✗"
        )
        detectedSpots = shot.candidates.map { ScreenPoint(it.x, it.y, it.timestampNs / 1_000_000L) }.shiftedToStill()
        shotDiagnostics = "$stats\nSaving recording…"
        // Frames are large; save in the background without holding up the result.
        scope.launch {
            val saved = withContext(Dispatchers.IO) { ShotDebugExporter.save(context, shot, flight) }
            shotDiagnostics = "$stats\n" + (saved?.let { "Saved $it" } ?: "Couldn't save recording")
        }
    }

    // Helper function to complete shot recording and open review screen
    val completeShotRecording: () -> Unit = {
        scope.launch {
            if (isRecording) analyzeRecordedShot()
            isShotRevealed = true
            val bitmap = shotStill ?: previewViewRef?.bitmap ?: Bitmap.createBitmap(
                1080, 1920, Bitmap.Config.ARGB_8888
            )

            val record = ShotRecord(
                club = selectedClub,
                tracerStyle = selectedTracerStyle.name,
                launchLocation = gpsLaunch,
                landingLocation = gpsLanding,
                distanceYards = distanceYards,
                distanceMeters = distanceMeters,
                points = detectedPoints
            )

            val savedUri = SnapshotExporter.createAnnotatedSnapshot(context, bitmap, record)
            val finalRecord = record.copy(snapshotUri = savedUri?.toString())

            repository.saveShot(finalRecord)
            onNavigateToReview(finalRecord)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. Camera Preview Surface
        CameraPreviewView(
            isFlashEnabled = isFlashEnabled,
            captureSession = captureSession,
            onPreviewReady = { pView -> previewViewRef = pView }
        )

        // Once recording stops, freeze on the still from launch (or from the tap).
        val still = shotStill
        if (still != null && !isRecording) {
            Image(
                bitmap = still.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize()
            )
        }

        // 2. Ball Tracer & Tap-to-Lock Target Overlay
        BallTracerCanvas(
            points = detectedPoints,
            style = selectedTracerStyle,
            isRecording = isRecording,
            isEditMode = isApexEditMode,
            tappedBallLocation = tappedBallLocation,
            ballRadius = teeBallRadius,
            debugSpots = if (isBallFlightMissing) detectedSpots else emptyList(),
            onTapBallLocation = { pt ->
                if (isAnalyzing) {
                    // Ignore taps until the flight has been worked out.
                } else if (isRecording) {
                    scope.launch {
                        analyzeRecordedShot()
                        isShotRevealed = true
                    }
                } else if (tappedBallLocation != null || detectedPoints.isNotEmpty()) {
                    isShotRevealed = true
                } else {
                    tappedBallLocation = pt
                    detectedPoints = emptyList()
                    isShotRevealed = false
                    isBallLaunched = false
                    isBallFlightMissing = false
                    shotDiagnostics = null
                    detectedSpots = emptyList()
                    tapStill = previewViewRef?.bitmap
                    shotStill = tapStill
                    stillShift = 0f to 0f
                    teeBallRadius = null
                    tapMissedBall = false
                    captureSession.arm(pt.x, pt.y)
                    isRecording = true
                    locationManager.recordLaunchLocation()
                }
            },
            onPointAdjusted = { updated -> detectedPoints = updated }
        )

        // 3. Top: shot info row with the settings gear, and the status banner below it
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ShotTelemetryHUD(
                    distanceYards = distanceYards,
                    distanceMeters = distanceMeters,
                    showShotDistance = isShotRevealed,
                    clubName = selectedClub,
                    gpsLaunch = gpsLaunch,
                    gpsLanding = gpsLanding,
                    onClubClick = { showClubSheet = true },
                    onLockTeeClick = {
                        val locked = locationManager.recordLaunchLocation()
                        if (locked == null) locationManager.startLocationUpdates()
                    },
                    onLockLandingClick = {
                        locationManager.recordLandingLocation()
                    },
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { showSettings = true },
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(GolfDarkCard.copy(alpha = 0.85f))
                ) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
                }
            }

            // Status banner; tapping it starts over
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isRecording) GolfNeonRed.copy(alpha = 0.35f) else GolfDarkCard.copy(alpha = 0.85f)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clickable { resetShot() }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isRecording) Icons.Default.FiberManualRecord else Icons.Default.TouchApp,
                        contentDescription = null,
                        tint = if (isRecording) GolfNeonRed else GolfNeonCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = when {
                                isAnalyzing -> "⏳ Finding ball flight…"
                                isRecording && isBallLaunched -> "🔴 Ball launched! Tap screen when it lands"
                                isRecording && teeBallRadius == null -> "🔍 Looking for the ball…"
                                isRecording -> "🔴 RECORDING • Ball locked • Swing, then tap screen when done"
                                tapMissedBall -> "⚠️ No ball found there • Tap right on the ball"
                                isBallFlightMissing && !launchSeen -> "⚠️ Didn't see the ball leave • Tap here to retry"
                                isBallFlightMissing -> "⚠️ Ball flight not found • Tap here to retry"
                                tappedBallLocation != null || detectedPoints.isNotEmpty() -> "⛳ Shot complete! Tap screen to show distance"
                                else -> "⛳ Tap golf ball on screen to start recording"
                            },
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (!isRecording && !isAnalyzing) {
                            shotDiagnostics?.let {
                                Text(text = it, color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }

        // 4. Bottom: reset, plus Save & Review once a shot has been recorded
        val shotReady = !isRecording && !isAnalyzing && (detectedPoints.isNotEmpty() || tappedBallLocation != null)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    resetShot()
                    locationManager.reset()
                },
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(GolfDarkCard.copy(alpha = 0.85f))
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Reset Shot", tint = Color.White)
            }

            if (shotReady) {
                Spacer(modifier = Modifier.weight(1f))
                Button(
                    onClick = { completeShotRecording() },
                    shape = RoundedCornerShape(22.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GolfNeonLime),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    modifier = Modifier.height(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = GolfDarkBg,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Save & Review", color = GolfDarkBg, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.weight(1f))
                // Balances the reset button so Save & Review sits in the middle.
                Spacer(modifier = Modifier.size(44.dp))
            }
        }
    }

    // Club Selection Bottom Sheet
    if (showClubSheet) {
        ModalBottomSheet(onDismissRequest = { showClubSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Select Club", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                ClubType.values().forEach { club ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedClub = club.displayName
                                showClubSheet = false
                            }
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(club.displayName, fontSize = 16.sp, color = Color.White)
                        Text(club.category, fontSize = 14.sp, color = GolfTextSecondary)
                    }
                }
            }
        }
    }

    // Settings pop-up: flash, tracer style, curve tuning and the demo flight
    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            containerColor = GolfDarkCard,
            title = { Text("Settings", fontWeight = FontWeight.Bold, color = Color.White) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SettingSwitch(
                        icon = if (isFlashEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                        label = "Flash",
                        checked = isFlashEnabled,
                        onCheckedChange = { isFlashEnabled = it }
                    )
                    SettingSwitch(
                        icon = Icons.Default.Edit,
                        label = "Tune curve by dragging",
                        checked = isApexEditMode,
                        onCheckedChange = { isApexEditMode = it }
                    )

                    Text("Tracer style", color = GolfTextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    TracerStyle.values().forEach { style ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (style == selectedTracerStyle) GolfDarkSurface else Color.Transparent)
                                .clickable { selectedTracerStyle = style }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(Color(style.colorHex))
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(style.displayName, fontSize = 15.sp, color = Color.White, modifier = Modifier.weight(1f))
                            if (style == selectedTracerStyle) {
                                Icon(Icons.Default.Check, contentDescription = "Selected", tint = GolfNeonLime)
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            showSettings = false
                            captureSession.reset()
                            tapStill = null
                            shotStill = null
                            isRecording = false
                            isBallFlightMissing = false
                            tappedBallLocation = ScreenPoint(0.5f, 0.8f)
                            detectedPoints = TrajectoryMath.generateDefaultTracer(1080f, 1920f)
                            if (distanceYards == 0.0) locationManager.setManualDistanceYards(245.0)
                            isShotRevealed = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.AutoFixHigh, contentDescription = null, tint = GolfNeonCyan, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Show demo flight", color = GolfNeonCyan)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettings = false }) { Text("Done", color = GolfNeonLime) }
            }
        )
    }
}

@Composable
private fun SettingSwitch(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = if (checked) GolfNeonGold else Color.White, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Text(label, fontSize = 15.sp, color = Color.White, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
