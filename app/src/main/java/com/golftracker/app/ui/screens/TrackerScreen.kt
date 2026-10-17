package com.golftracker.app.ui.screens

import android.graphics.Bitmap
import android.os.SystemClock
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
import com.golftracker.app.export.SnapshotExporter
import com.golftracker.app.location.DeviceOrientation
import com.golftracker.app.location.LocationTrackerManager
import com.golftracker.app.model.*
import com.golftracker.app.repository.ShotProcessor
import com.golftracker.app.repository.ShotRepository
import com.golftracker.app.tracker.AutoShotDetector
import com.golftracker.app.tracker.BallFlightFitter
import com.golftracker.app.tracker.BallTrackerAnalyzer
import com.golftracker.app.tracker.CapturedShot
import com.golftracker.app.tracker.ImpactSoundDetector
import com.golftracker.app.tracker.LaunchPhysics
import com.golftracker.app.tracker.ShotTrigger
import com.golftracker.app.tracker.TrajectoryMath
import com.golftracker.app.ui.components.BallLocatorDialog
import com.golftracker.app.ui.components.BallTracerCanvas
import com.golftracker.app.ui.components.CameraPreviewView
import com.golftracker.app.ui.components.ShotTelemetryHUD
import com.golftracker.app.ui.theme.*
import com.golftracker.app.video.ShotVideoRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the tracker is doing: waiting for a shot (showing the last one, if any), capturing one, or working it out. */
private enum class Phase { LISTENING, CAPTURING, ANALYZING }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackerScreen(
    locationManager: LocationTrackerManager,
    repository: ShotRepository,
    processor: ShotProcessor,
    onNavigateToReview: (ShotRecord) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var phase by remember { mutableStateOf(Phase.LISTENING) }
    var isPaused by remember { mutableStateOf(false) }
    var isListening by remember { mutableStateOf(false) }
    var isMicOn by remember { mutableStateOf(false) }
    var isVideoAvailable by remember { mutableStateOf<Boolean?>(null) }
    var isFlashEnabled by remember { mutableStateOf(false) }
    var isApexEditMode by remember { mutableStateOf(false) }
    var isShotRevealed by remember { mutableStateOf(false) }
    var isBallFlightMissing by remember { mutableStateOf(false) }
    var isRetracing by remember { mutableStateOf(false) }
    // Diagnostics from the last shot: what the detector saw, where it was saved, how the video went.
    var shotStats by remember { mutableStateOf<String?>(null) }
    var saveNote by remember { mutableStateOf<String?>(null) }
    var videoNote by remember { mutableStateOf<String?>(null) }
    var detectedSpots by remember { mutableStateOf<List<ScreenPoint>>(emptyList()) }

    var selectedClub by remember { mutableStateOf(ClubType.DRIVER.displayName) }
    var selectedTracerStyle by remember { mutableStateOf(TracerStyle.PRO_LIME) }
    var detectedPoints by remember { mutableStateOf<List<ScreenPoint>>(emptyList()) }
    var teeLocation by remember { mutableStateOf<ScreenPoint?>(null) }
    // The shot on screen, once saved.
    var currentShotId by remember { mutableStateOf<String?>(null) }

    var showClubSheet by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showLimitPrompt by remember { mutableStateOf(false) }

    val currentLocation by locationManager.currentLocation.collectAsState()
    val gpsLaunch by locationManager.launchLocation.collectAsState()
    val gpsLanding by locationManager.landingLocation.collectAsState()
    val distanceYards by locationManager.shotDistanceYards.collectAsState()
    val distanceMeters by locationManager.shotDistanceMeters.collectAsState()

    val shots by repository.shots.collectAsState()
    // Shots handed to the processor but not saved yet still count towards the recording limit.
    var pendingSaves by remember { mutableIntStateOf(0) }
    val recordingCount = ShotRepository.recordingCount(shots) + pendingSaves
    fun atRecordingLimit() = ShotRepository.recordingCount(repository.shots.value) + pendingSaves >= ShotRepository.MAX_RECORDINGS

    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }

    // For the ball locator: which way the phone faced while filming, and the camera's field of view.
    val orientation = remember { DeviceOrientation(context) }
    DisposableEffect(orientation) {
        orientation.start()
        onDispose { orientation.stop() }
    }
    val heading by orientation.headingDegrees.collectAsState()
    var verticalFov by remember { mutableStateOf<Double?>(null) }
    var launchRotation by remember { mutableStateOf<FloatArray?>(null) }
    var ballLanding by remember { mutableStateOf<LaunchPhysics.Landing?>(null) }
    var locatorProblem by remember { mutableStateOf<String?>(null) }
    var showLocator by remember { mutableStateOf(false) }

    // The finished shot is shown on a still from just after the strike rather than on the live camera,
    // so the tracer sits over the swing.
    var shotStill by remember { mutableStateOf<Bitmap?>(null) }
    var teeBallRadius by remember { mutableStateOf<Float?>(null) }
    var launchSeen by remember { mutableStateOf(false) }
    // How far the camera had moved when the still was taken; the tracer is shifted by it to line up.
    var stillShift by remember { mutableStateOf(0f to 0f) }

    // Watches the camera and listens for the strike; a shot starts by itself.
    val events = remember { Channel<ShotTrigger.Event>(Channel.UNLIMITED) }
    val detector = remember { AutoShotDetector { events.trySend(it) } }
    val impactSound = remember { ImpactSoundDetector(context) { detector.onImpactSound(it) } }
    val recorder = remember { ShotVideoRecorder(context) }
    val analyzer = remember { BallTrackerAnalyzer(wantsFrames = { detector.wantsFrames }, onFrame = detector::offerFrame) }
    DisposableEffect(Unit) {
        onDispose {
            detector.close()
            impactSound.stop()
            recorder.discard()
        }
    }

    fun List<ScreenPoint>.shiftedToStill(): List<ScreenPoint> {
        val (dx, dy) = stillShift
        return if (dx == 0f && dy == 0f) this else map { it.copy(x = it.x + dx, y = it.y + dy) }
    }

    fun startListening() {
        if (isPaused) return
        if (atRecordingLimit()) {
            showLimitPrompt = true
            return
        }
        recorder.start()
        if (!isMicOn) isMicOn = impactSound.start()
        detector.watchWithoutSound(!isMicOn)
        detector.startListening()
        isListening = true
    }

    fun stopListening() {
        detector.stopListening()
        recorder.discard()
        impactSound.stop()
        isMicOn = false
        isListening = false
        phase = Phase.LISTENING
    }

    fun clearShot() {
        detectedPoints = emptyList()
        teeLocation = null
        currentShotId = null
        isShotRevealed = false
        isBallFlightMissing = false
        isRetracing = false
        shotStats = null
        saveNote = null
        videoNote = null
        detectedSpots = emptyList()
        shotStill = null
        stillShift = 0f to 0f
        teeBallRadius = null
        launchRotation = null
        ballLanding = null
        locatorProblem = null
    }

    // Back to the live camera; a shot being captured is abandoned.
    fun resetShot() {
        clearShot()
        if (phase == Phase.CAPTURING) {
            stopListening()
            startListening()
        }
    }

    // Where the ball came down, worked out from its launch on camera, for the ball locator.
    fun estimateLanding(fitted: BallFlightFitter.Flight?, ballRadius: Float?) {
        ballLanding = null
        val tee = gpsLaunch
        val rotation = launchRotation
        val fov = verticalFov
        locatorProblem = when {
            fitted == null -> "No ball flight was traced, so there's nothing to locate."
            tee == null -> "There was no GPS fix when the shot was taken. Wait for GPS before the next shot."
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

    // Finds the ball's flight among everything that moved, then saves the shot with its recording.
    suspend fun analyzeCapturedShot(shot: CapturedShot) {
        phase = Phase.ANALYZING
        val clip = scope.async { recorder.stop() }
        val fitted = withContext(Dispatchers.Default) { BallFlightFitter.fitFlight(shot) }
        val video = clip.await()

        if (fitted == null && !shot.heardImpact) {
            // Something bright went from a still spot during some movement, but nothing was heard and
            // nothing flew: not a shot. Carry on listening.
            video?.file?.delete()
            clearShot()
            phase = Phase.LISTENING
            shotStats = "Saw movement but no ball flight • still listening"
            startListening()
            return
        }

        val flight = fitted?.tracer ?: emptyList()
        detectedPoints = flight.shiftedToStill()
        estimateLanding(fitted, shot.ballRadius)
        isBallFlightMissing = flight.isEmpty()
        launchSeen = shot.launchTimestampNs != null
        val frames = shot.thumbnails
        val seconds = if (frames.size > 1) (frames.last().timestampNs - frames.first().timestampNs) / 1e9 else 0.0
        val fps = if (seconds > 0) shot.framesProcessed / seconds else 0.0
        shotStats = "%d spots • %.1f s at %.0f fps • strike %s • ball left %s".format(
            shot.candidates.size, seconds, fps,
            if (shot.heardImpact) "heard" else "seen",
            if (shot.launchTimestampNs != null) "✓" else "✗"
        )
        detectedSpots = shot.candidates.map { ScreenPoint(it.x, it.y, it.timestampNs / 1_000_000L) }.shiftedToStill()

        val record = ShotRecord(
            club = selectedClub,
            tracerStyle = selectedTracerStyle.name,
            launchLocation = gpsLaunch,
            landingLocation = gpsLanding,
            distanceYards = distanceYards,
            distanceMeters = distanceMeters,
            points = detectedPoints
        )
        currentShotId = record.id
        pendingSaves++
        saveNote = "Saving recording…"
        isRetracing = video != null
        videoNote = if (video != null) "Tracing again from the full video…" else "No video for this shot"
        processor.process(ShotProcessor.NewShot(record, shot, fitted, video, shotStill, stillShift))

        phase = Phase.LISTENING
        startListening()
    }

    suspend fun handle(event: ShotTrigger.Event) {
        when (event) {
            is ShotTrigger.Event.Started -> {
                clearShot()
                phase = Phase.CAPTURING
                stillShift = event.stillShiftX to event.stillShiftY
                shotStill = previewViewRef?.bitmap
                teeLocation = listOf(ScreenPoint(event.ball.x, event.ball.y)).shiftedToStill().first()
                teeBallRadius = event.ball.radius
                launchRotation = orientation.rotationMatrix()
                locationManager.recordLaunchLocation()
            }
            is ShotTrigger.Event.Captured -> if (phase == Phase.CAPTURING) analyzeCapturedShot(event.shot)
            is ShotTrigger.Event.Missed -> if (currentShotId == null && phase == Phase.LISTENING) {
                shotStats = "Heard a strike but didn't see a ball leave • still listening"
            }
        }
    }

    LaunchedEffect(Unit) {
        for (event in events) handle(event)
    }

    // The background work on saved shots: tell the screen when the shot on it is saved or re-traced.
    LaunchedEffect(Unit) {
        processor.updates.collect { update ->
            if (update is ShotProcessor.Update.Saved) pendingSaves = (pendingSaves - 1).coerceAtLeast(0)
            if (update.shotId != currentShotId) return@collect
            when (update) {
                is ShotProcessor.Update.Saved -> saveNote = "Saved to history" + (update.debugExport?.let { " • frames in $it" } ?: "")
                is ShotProcessor.Update.Retraced -> {
                    isRetracing = false
                    videoNote = update.note
                    if (update.points != null && update.flight != null) {
                        detectedPoints = update.points
                        isBallFlightMissing = false
                        estimateLanding(update.flight, teeBallRadius)
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        repository.loadShots()
        startListening()
    }

    // A recording running long with nothing happening is started afresh, so shot videos stay short.
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            val quietNs = SystemClock.elapsedRealtimeNanos() - detector.lastActivityNs
            if (isListening && phase == Phase.LISTENING && recorder.isRecording &&
                recorder.ageNs > MAX_IDLE_RECORDING_NS && quietNs > QUIET_BEFORE_RESTART_NS
            ) {
                recorder.discard()
                recorder.start()
            }
        }
    }

    // Saves the shot on screen with any changes (club, distance, tuned curve) and opens its review.
    val reviewShot: () -> Unit = {
        scope.launch {
            isShotRevealed = true
            val id = currentShotId
            val saved = if (id != null) {
                val base = repository.find(id) ?: return@launch
                processor.saveEdits(
                    base.copy(
                        club = selectedClub,
                        tracerStyle = selectedTracerStyle.name,
                        launchLocation = gpsLaunch ?: base.launchLocation,
                        landingLocation = gpsLanding,
                        distanceYards = distanceYards,
                        distanceMeters = distanceMeters,
                        points = detectedPoints
                    ),
                    shotStill
                )
            } else {
                // The demo flight: no recording, just a snapshot.
                val bitmap = shotStill ?: previewViewRef?.bitmap ?: Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
                val record = ShotRecord(
                    club = selectedClub,
                    tracerStyle = selectedTracerStyle.name,
                    launchLocation = gpsLaunch,
                    landingLocation = gpsLanding,
                    distanceYards = distanceYards,
                    distanceMeters = distanceMeters,
                    points = detectedPoints
                )
                val uri = SnapshotExporter.createAnnotatedSnapshot(context, bitmap, record)
                record.copy(snapshotUri = uri?.toString()).also { repository.saveShot(it) }
            }
            onNavigateToReview(saved)
        }
    }

    val showingShot = phase == Phase.LISTENING && (detectedPoints.isNotEmpty() || currentShotId != null)

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. Camera Preview Surface
        CameraPreviewView(
            isFlashEnabled = isFlashEnabled,
            analyzer = analyzer,
            onPreviewReady = { pView -> previewViewRef = pView },
            onFieldOfView = { verticalFov = it },
            onVideoCapture = { capture ->
                recorder.attach(capture)
                isVideoAvailable = capture != null
                if (isListening) recorder.start()
            }
        )

        // Once a shot has been captured, freeze on the still from just after the strike.
        val still = shotStill
        if (still != null && phase != Phase.CAPTURING) {
            Image(
                bitmap = still.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize()
            )
        }

        // 2. Ball tracer, and the ring on the ball while its shot is captured
        BallTracerCanvas(
            points = detectedPoints,
            style = selectedTracerStyle,
            isRecording = phase == Phase.CAPTURING,
            isEditMode = isApexEditMode,
            tappedBallLocation = teeLocation,
            ballRadius = teeBallRadius,
            debugSpots = if (isBallFlightMissing) detectedSpots else emptyList(),
            onTapBallLocation = {
                when {
                    // Ends the capture early, once the ball has landed.
                    phase == Phase.CAPTURING -> detector.finishCapture()
                    showingShot -> isShotRevealed = true
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

            // Status banner; tapping it starts over, or offers to make room once the recordings are full
            val limitReached = !isListening && !isPaused && recordingCount >= ShotRepository.MAX_RECORDINGS
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (phase == Phase.CAPTURING) GolfNeonRed.copy(alpha = 0.35f) else GolfDarkCard.copy(alpha = 0.85f)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clickable { if (limitReached) showLimitPrompt = true else resetShot() }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = when {
                            phase == Phase.CAPTURING -> Icons.Default.FiberManualRecord
                            isListening -> Icons.Default.Hearing
                            else -> Icons.Default.PauseCircle
                        },
                        contentDescription = null,
                        tint = if (phase == Phase.CAPTURING) GolfNeonRed else GolfNeonCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = when {
                                phase == Phase.ANALYZING -> "⏳ Finding ball flight…"
                                phase == Phase.CAPTURING -> "🔴 Shot detected! Following the ball • Tap screen when it lands"
                                isPaused -> "⏸ Paused • Tap ▶ below to listen for shots"
                                limitReached -> "⚠️ ${ShotRepository.MAX_RECORDINGS} shot recordings stored • Tap here to free up space"
                                showingShot && isBallFlightMissing && !launchSeen -> "⚠️ Didn't see the ball leave • Listening for the next shot"
                                showingShot && isBallFlightMissing -> "⚠️ Ball flight not found • Listening for the next shot"
                                showingShot -> "⛳ Shot saved! Tap screen for distance • Listening for the next shot"
                                else -> "👂 Listening • Just hit: the shot is picked up by the strike's sound and the ball leaving"
                            },
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        val details = if (phase == Phase.LISTENING && !isPaused) {
                            listOfNotNull(
                                shotStats, saveNote, videoNote,
                                if (!showingShot) "🎤 Mic ${if (isMicOn) "on" else "off (watching only)"} • 🎥 Video ${
                                    when (isVideoAvailable) { true -> "on"; false -> "not available"; null -> "starting" }
                                } • Recordings $recordingCount/${ShotRepository.MAX_RECORDINGS}" else null
                            )
                        } else emptyList()
                        details.forEach { Text(text = it, color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp) }
                    }
                }
            }
        }

        // 4. Bottom: reset and pause, plus Locate Ball and Review once a shot has been recorded
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
            IconButton(
                onClick = {
                    if (isPaused) {
                        isPaused = false
                        startListening()
                    } else {
                        isPaused = true
                        clearShot()
                        stopListening()
                    }
                },
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(GolfDarkCard.copy(alpha = 0.85f))
            ) {
                Icon(
                    if (isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                    contentDescription = if (isPaused) "Listen for shots" else "Pause listening",
                    tint = Color.White
                )
            }

            if (showingShot) {
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
                    onClick = reviewShot,
                    shape = RoundedCornerShape(22.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = GolfNeonLime),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    modifier = Modifier.height(44.dp)
                ) {
                    if (isRetracing) {
                        CircularProgressIndicator(color = GolfDarkBg, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    } else {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = GolfDarkBg,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Review", color = GolfDarkBg, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (showLimitPrompt) {
        val toFree = ShotRepository.earliestRecordings(shots, ShotRepository.RECORDINGS_TO_FREE)
        AlertDialog(
            onDismissRequest = { showLimitPrompt = false },
            containerColor = GolfDarkCard,
            icon = { Icon(Icons.Default.VideoLibrary, contentDescription = null, tint = GolfNeonGold) },
            title = { Text("Recording limit reached", fontWeight = FontWeight.Bold, color = Color.White) },
            text = {
                Text(
                    "${ShotRepository.MAX_RECORDINGS} shots have recordings saved, the most kept on the phone. " +
                        "Remove the recordings of the earliest ${toFree.size} shots to keep recording? " +
                        "Those shots stay in your history with their tracer snapshots and distances. " +
                        "Share any you want to keep from the Shot Log first.",
                    color = GolfTextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showLimitPrompt = false
                    scope.launch {
                        repository.deleteRecordings(toFree.map { it.id })
                        startListening()
                    }
                }) { Text("Remove ${toFree.size} recordings", color = GolfNeonLime) }
            },
            dismissButton = {
                TextButton(onClick = { showLimitPrompt = false }) { Text("Not now", color = Color.White) }
            }
        )
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
                            resetShot()
                            teeLocation = ScreenPoint(0.5f, 0.8f)
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

/** A recording longer than this with nothing going on is started afresh. */
private const val MAX_IDLE_RECORDING_NS = 60_000_000_000L
private const val QUIET_BEFORE_RESTART_NS = 3_000_000_000L
