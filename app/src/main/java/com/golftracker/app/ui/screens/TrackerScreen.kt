package com.golftracker.app.ui.screens

import android.graphics.Bitmap
import androidx.camera.view.PreviewView
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.golftracker.app.export.SnapshotExporter
import com.golftracker.app.location.LocationTrackerManager
import com.golftracker.app.model.*
import com.golftracker.app.repository.ShotRepository
import com.golftracker.app.tracker.TrajectoryMath
import com.golftracker.app.ui.components.BallTracerCanvas
import com.golftracker.app.ui.components.CameraPreviewView
import com.golftracker.app.ui.components.ShotTelemetryHUD
import com.golftracker.app.ui.theme.*
import kotlinx.coroutines.launch

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

    var selectedClub by remember { mutableStateOf(ClubType.DRIVER.displayName) }
    var selectedTracerStyle by remember { mutableStateOf(TracerStyle.PRO_LIME) }
    var detectedPoints by remember { mutableStateOf<List<ScreenPoint>>(emptyList()) }
    var tappedBallLocation by remember { mutableStateOf<ScreenPoint?>(null) }

    var showClubSheet by remember { mutableStateOf(false) }
    var showStyleSheet by remember { mutableStateOf(false) }

    val currentLocation by locationManager.currentLocation.collectAsState()
    val gpsLaunch by locationManager.launchLocation.collectAsState()
    val gpsLanding by locationManager.landingLocation.collectAsState()
    val distanceYards by locationManager.shotDistanceYards.collectAsState()
    val distanceMeters by locationManager.shotDistanceMeters.collectAsState()

    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }

    // Helper function to complete shot recording and open review screen
    val completeShotRecording: () -> Unit = {
        isRecording = false
        scope.launch {
            val bitmap = previewViewRef?.bitmap ?: Bitmap.createBitmap(
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
            isRecording = isRecording,
            isFlashEnabled = isFlashEnabled,
            tappedBallLocation = tappedBallLocation,
            onBallDetected = { pt ->
                if (isRecording) {
                    detectedPoints = detectedPoints + pt
                }
            },
            onPreviewReady = { pView -> previewViewRef = pView }
        )

        // 2. Ball Tracer & Tap-to-Lock Target Overlay
        BallTracerCanvas(
            points = detectedPoints,
            style = selectedTracerStyle,
            isRecording = isRecording,
            isEditMode = isApexEditMode,
            tappedBallLocation = tappedBallLocation,
            onTapBallLocation = { pt ->
                // AUTOMATICALLY START RECORDING UPON TAPPING BALL LOCATION ON SCREEN
                tappedBallLocation = pt
                isRecording = true
                detectedPoints = emptyList()
                locationManager.recordLaunchLocation()
            },
            onPointAdjusted = { updated -> detectedPoints = updated }
        )

        // 3. Top Action Controls (Flash, Style, Edit Apex & Tap Prompt Banner)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { isFlashEnabled = !isFlashEnabled },
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(GolfDarkCard.copy(alpha = 0.8f))
                ) {
                    Icon(
                        imageVector = if (isFlashEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                        contentDescription = "Flash",
                        tint = if (isFlashEnabled) GolfNeonGold else Color.White
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Style Picker Pill
                    Button(
                        onClick = { showStyleSheet = true },
                        colors = ButtonDefaults.buttonColors(containerColor = GolfDarkCard.copy(alpha = 0.8f)),
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(Color(selectedTracerStyle.colorHex))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(selectedTracerStyle.displayName, fontSize = 12.sp, color = Color.White)
                    }

                    // Edit Apex Curve Toggle
                    IconButton(
                        onClick = { isApexEditMode = !isApexEditMode },
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (isApexEditMode) GolfNeonCyan else GolfDarkCard.copy(alpha = 0.8f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Tune Curve",
                            tint = if (isApexEditMode) GolfDarkBg else Color.White
                        )
                    }
                }
            }

            // Automatic Tap Ball Prompt Banner
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isRecording) GolfNeonRed.copy(alpha = 0.35f) else GolfDarkCard.copy(alpha = 0.85f)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clickable {
                        tappedBallLocation = null
                        isRecording = false
                        detectedPoints = emptyList()
                    }
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
                    Text(
                        text = if (isRecording) "🔴 RECORDING • Ball locked • Swing when ready!" else "⛳ Tap golf ball on screen to start recording",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // 4. Bottom Dashboard & Streamlined Controls Container
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Live Telemetry HUD
            ShotTelemetryHUD(
                distanceYards = distanceYards,
                distanceMeters = distanceMeters,
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
                }
            )

            // Streamlined Control Row (Manual Record Button Removed)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Reset / Clear Ball Location Button
                IconButton(
                    onClick = {
                        detectedPoints = emptyList()
                        tappedBallLocation = null
                        isRecording = false
                        locationManager.reset()
                    },
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .background(GolfDarkCard.copy(alpha = 0.9f))
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Reset Shot", tint = Color.White)
                }

                // Center Action Button: Complete & Review Shot (or Prompt to Tap)
                Button(
                    onClick = {
                        if (isRecording || detectedPoints.isNotEmpty() || tappedBallLocation != null) {
                            completeShotRecording()
                        }
                    },
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRecording || detectedPoints.isNotEmpty()) GolfNeonLime else GolfDarkCard.copy(alpha = 0.85f)
                    ),
                    modifier = Modifier
                        .height(50.dp)
                        .weight(1f)
                        .padding(horizontal = 12.dp)
                ) {
                    Icon(
                        imageVector = if (isRecording || detectedPoints.isNotEmpty()) Icons.Default.CheckCircle else Icons.Default.TouchApp,
                        contentDescription = null,
                        tint = if (isRecording || detectedPoints.isNotEmpty()) GolfDarkBg else GolfNeonCyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isRecording || detectedPoints.isNotEmpty()) "Save Shot & Review" else "Tap Ball to Start",
                        color = if (isRecording || detectedPoints.isNotEmpty()) GolfDarkBg else Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Demo Optical Simulation Trigger
                IconButton(
                    onClick = {
                        isRecording = true
                        detectedPoints = TrajectoryMath.generateDefaultTracer(1080f, 1920f)
                        if (distanceYards == 0.0) locationManager.setManualDistanceYards(245.0)
                    },
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .background(GolfDarkCard.copy(alpha = 0.9f))
                ) {
                    Icon(Icons.Default.AutoFixHigh, contentDescription = "Simulate Flight", tint = GolfNeonCyan)
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

    // Tracer Style Selection Bottom Sheet
    if (showStyleSheet) {
        ModalBottomSheet(onDismissRequest = { showStyleSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("Tracer Visual Style", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                TracerStyle.values().forEach { style ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedTracerStyle = style
                                showStyleSheet = false
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(Color(style.colorHex))
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Text(style.displayName, fontSize = 16.sp, color = Color.White)
                    }
                }
            }
        }
    }
}
