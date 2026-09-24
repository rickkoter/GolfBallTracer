package com.golftracker.app.ui.components

import android.graphics.Bitmap
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.golftracker.app.model.ScreenPoint
import com.golftracker.app.tracker.BallTrackerAnalyzer
import java.util.concurrent.Executors

@Composable
fun CameraPreviewView(
    isRecording: Boolean,
    isFlashEnabled: Boolean,
    tappedBallLocation: ScreenPoint?,
    onBallDetected: (ScreenPoint) -> Unit,
    onPreviewReady: (PreviewView) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }

    val analyzer = remember(onBallDetected) { BallTrackerAnalyzer(onBallDetected) }

    LaunchedEffect(tappedBallLocation) {
        if (tappedBallLocation != null) {
            analyzer.setInitialBallLocation(tappedBallLocation.x, tappedBallLocation.y)
        }
    }

    LaunchedEffect(isRecording) {
        if (isRecording) {
            if (tappedBallLocation != null) {
                analyzer.setInitialBallLocation(tappedBallLocation.x, tappedBallLocation.y)
            } else {
                analyzer.resetTracker()
            }
        }
    }

    DisposableEffect(Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            cameraExecutor.shutdown()
        }
    }

    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                onPreviewReady(this)
            }
        },
        update = { previewView ->
            val provider = cameraProvider ?: return@AndroidView

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, analyzer)
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalyzer
                )
                camera?.cameraControl?.enableTorch(isFlashEnabled)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        },
        modifier = modifier.fillMaxSize()
    )
}
