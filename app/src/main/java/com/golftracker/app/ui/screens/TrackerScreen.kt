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
import com.golftracker.app.location.DeviceOrientation
import com.golftracker.app.location.LocationTrackerManager
import com.golftracker.app.model.*
import com.golftracker.app.repository.ShotRepository
import com.golftracker.app.tracker.BallFlightFitter
import com.golftracker.app.tracker.LaunchPhysics
import com.golftracker.app.tracker.ShotCaptureSession
import com.golftracker.app.tracker.TrajectoryMath
import com.golftracker.app.ui.components.BallLocatorDialog
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
    // For the ball locator: which way the phone faced while filming, and the camera's field of view.
    val orientation = remember { DeviceOrientation(context) }
    DisposableEffect(orientation) {
        orientation.start()
        onDispose { orientation.stop() }
    }
    val heading by orientation.headingDegrees.collectAsState()
    var verticalFov by remember { mutableStateOf<Double?>(null) }
    var tapRotation by remember { mutableStateOf<FloatArray?>(null) }
    var launchRotation by remember { mutableStateOf<FloatArray?>(null) }
    var ballLanding by remember { mutableStateOf<LaunchPhysics.Landing?>(null) }
    var locatorProblem by remember { mutableStateOf<String?>(null) }
    var showLocator by remember { mutableStateOf(false) }

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
            val rotation = if (launched) orientation.rotationMatrix() else null
            // Called on the camera thread; grab the preview on the main thread.
            scope.launch {
                launchRotation = rotation
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
        tapRotation = null
        launchRotation = null
        ballLanding = null
        locatorProblem = null
    }

    // Where the ball came down, worked out from its launch on camera, for the ball locator.
    fun estimateLanding(fitted: BallFlightFitter.Flight?, ballRadius: Float?) {
        ballLanding = null
        val tee = gpsLaunch
        val rotation = launchRotation ?: tapRotation
        val fov = verticalFov
        locatorProblem = when {
            fitted == null -> "No ball flight was traced, so there's nothing to locate."
            tee == null -> "There was no GPS fix when you tapped the ball. Wait for GPS before the next shot."
            rotation == null -> "The phone's compass wasn't available while filming."
            fov == null -> "The camera's field of view isn't known on this phone."
            ballRadius == null -> "The ball on the tee wasn't measured."
            else -> null
        }
        if (locatorProblem != null || fitted == null || tee == null || rotation == null || fov == null || ballRadius == null) return
        val declination = android.hardware.GeomagneticField(
            tee.latitude.toFloat(), tee.longitude.toFloat(), tee.altitude.toFloat(), System.currentTimeMillis()
        ).declination.toDouble()
        ballLanding = LaunchPhysics.estimateLanding(fitted.launch, ballRadius, fov, rotation, declination, tee)
        if (ballLanding == null) locatorProblem = "The launch speed didn't look like a real shot, so there's no landing estimate."
    }

    // Stops recording and finds the ball's flight among everything that moved.
    suspend fun analyzeRecordedShot() {
        isRecording = false
        val shot = captureSession.stop()
        isAnalyzing = true
        val fitted = withContext(Dispatchers.Default) { BallFlightFitter.fitFlight(shot) }
        val flight = fitted?.tracer ?: emptyList()
        detectedPoints = flight.shiftedToStill()
        estimateLanding(fitted, shot.ballRadius)
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
            onPreviewReady = { pView -> previewViewRef = pView },
            onFieldOfView = { verticalFov = it }
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
                    tapRotation = orientation.rotationMatrix()
                    launchRotation = null
                    ballLanding = null
                    locatorProblem = null
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
            horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                if (detectedPoints.isNotEmpty()) {
                    Button(
                        onClick = { showLocator = true },
                        shape = RoundedCornerShape(22.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = GolfDarkCard.copy(alpha = 0.9f)),
                        contentPadding = PaddingValues(horizontal = 14.dp),
                        modifier = Modifier.height(44.dp)
                    ) {
                        Icon(Icons.Default.MyLocation, contentDescription = null, tint = GolfNeonCyan, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Locate Ball", color = GolfNeonCyan, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
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

    if (showLocator) {
        BallLocatorDialog(
            landing = ballLanding,
            unavailableReason = locatorProblem,
            here = currentLocation,
            headingDegrees = heading,
            onDismiss = { showLocator = false }
        )
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
                            // A pretend landing 30 m ahead of where the phone points, to try the ball locator.
                            val here = currentLocation
                            val facing = heading
                            if (here != null && facing != null) {
                                val declination = android.hardware.GeomagneticField(
                                    here.latitude.toFloat(), here.longitude.toFloat(), here.altitude.toFloat(), System.currentTimeMillis()
                                ).declination
                                val bearing = LaunchPhysics.normalizeDegrees((facing + declination).toDouble())
                                ballLanding = LaunchPhysics.Landing(
                                    position = LaunchPhysics.destination(here, bearing, 30.0),
                                    bearingDegrees = bearing,
                                    carryMeters = 22.0,
                                    totalMeters = 30.0,
                                    launchSpeedMps = 18.0,
                                    launchAngleDegrees = 32.0
                                )
                                locatorProblem = null
                            } else {
                                ballLanding = null
                                locatorProblem = "The demo needs a GPS fix and the compass to place a pretend ball."
                            }
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
