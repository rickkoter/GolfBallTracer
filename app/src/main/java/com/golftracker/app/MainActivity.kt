package com.golftracker.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.golftracker.app.location.LocationTrackerManager
import com.golftracker.app.model.ShotRecord
import com.golftracker.app.repository.ShotProcessor
import com.golftracker.app.repository.ShotRepository
import com.golftracker.app.ui.screens.HistoryScreen
import com.golftracker.app.ui.screens.ShotReviewScreen
import com.golftracker.app.ui.screens.TrackerScreen
import com.golftracker.app.ui.theme.GolfBallVisualTrackerTheme
import com.golftracker.app.ui.theme.GolfDarkBg
import com.golftracker.app.ui.theme.GolfNeonLime

class MainActivity : ComponentActivity() {

    private lateinit var locationManager: LocationTrackerManager
    private lateinit var repository: ShotRepository
    private lateinit var processor: ShotProcessor

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            locationManager.startLocationUpdates()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The screen stays on while the app is open: hands-free, nobody touches the phone, and a sleeping
        // screen would take the camera and microphone with it.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        locationManager = LocationTrackerManager(this)
        repository = ShotRepository(this)
        processor = ShotProcessor(applicationContext, repository)

        requestRequiredPermissions()

        setContent {
            GolfBallVisualTrackerTheme {
                MainAppScreen(
                    locationManager = locationManager,
                    repository = repository,
                    processor = processor
                )
            }
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            locationManager.startLocationUpdates()
        }
    }

    override fun onResume() {
        super.onResume()
        locationManager.startLocationUpdates()
    }

    override fun onPause() {
        super.onPause()
        locationManager.stopLocationUpdates()
    }
}

enum class AppTab {
    TRACKER,
    HISTORY,
    REVIEW
}

@Composable
fun MainAppScreen(
    locationManager: LocationTrackerManager,
    repository: ShotRepository,
    processor: ShotProcessor
) {
    var currentTab by remember { mutableStateOf(AppTab.TRACKER) }
    // Listening for the strike is on each time the app starts; the tracker's 🎤 button turns it off.
    var isMicEnabled by rememberSaveable { mutableStateOf(true) }
    var reviewShotRecord by remember { mutableStateOf<ShotRecord?>(null) }

    Scaffold(
        bottomBar = {
            if (currentTab != AppTab.REVIEW) {
                NavigationBar(
                    containerColor = GolfDarkBg,
                    tonalElevation = 8.dp
                ) {
                    NavigationBarItem(
                        selected = currentTab == AppTab.TRACKER,
                        onClick = { currentTab = AppTab.TRACKER },
                        icon = { Icon(Icons.Default.Videocam, contentDescription = "Camera Tracker") },
                        label = { Text("Tracer View") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = GolfNeonLime,
                            selectedTextColor = GolfNeonLime,
                            indicatorColor = GolfNeonLime.copy(alpha = 0.2f),
                            unselectedIconColor = Color.Gray,
                            unselectedTextColor = Color.Gray
                        )
                    )
                    NavigationBarItem(
                        selected = currentTab == AppTab.HISTORY,
                        onClick = { currentTab = AppTab.HISTORY },
                        icon = { Icon(Icons.Default.History, contentDescription = "Shot History") },
                        label = { Text("Shot Log") },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = GolfNeonLime,
                            selectedTextColor = GolfNeonLime,
                            indicatorColor = GolfNeonLime.copy(alpha = 0.2f),
                            unselectedIconColor = Color.Gray,
                            unselectedTextColor = Color.Gray
                        )
                    )
                }
            }
        },
        containerColor = GolfDarkBg
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (currentTab) {
                AppTab.TRACKER -> {
                    TrackerScreen(
                        locationManager = locationManager,
                        repository = repository,
                        processor = processor,
                        isMicEnabled = isMicEnabled,
                        onMicEnabledChange = { isMicEnabled = it },
                        onNavigateToReview = { shot ->
                            reviewShotRecord = shot
                            currentTab = AppTab.REVIEW
                        }
                    )
                }
                AppTab.HISTORY -> {
                    HistoryScreen(
                        repository = repository,
                        onSelectShot = { shot ->
                            reviewShotRecord = shot
                            currentTab = AppTab.REVIEW
                        }
                    )
                }
                AppTab.REVIEW -> {
                    // Leaving a shot's review goes to the shot history, however the review was opened.
                    BackHandler { currentTab = AppTab.HISTORY }
                    reviewShotRecord?.let { shot ->
                        ShotReviewScreen(
                            shotRecord = shot,
                            repository = repository,
                            onBack = { currentTab = AppTab.HISTORY },
                            onRecordNext = { currentTab = AppTab.TRACKER }
                        )
                    } ?: run {
                        currentTab = AppTab.HISTORY
                    }
                }
            }
        }
    }
}
