package com.golftracker.app.ui.components

import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.golftracker.app.tracker.BallTrackerAnalyzer
import com.golftracker.app.tracker.ShotCaptureSession
import java.util.concurrent.Executors

@Composable
fun CameraPreviewView(
    isFlashEnabled: Boolean,
    captureSession: ShotCaptureSession,
    onPreviewReady: (PreviewView) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }

    val analyzer = remember(captureSession) { BallTrackerAnalyzer(captureSession) }

    DisposableEffect(Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            cameraExecutor.shutdown()
        }
    }

    // Bind once the view has been laid out, since its ViewPort depends on its size.
    DisposableEffect(cameraProvider, previewView, analyzer) {
        val provider = cameraProvider
        val view = previewView
        if (provider != null && view != null) {
            view.post {
                val selector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .build()

                val preview = Preview.Builder()
                    .setResolutionSelector(selector)
                    .build()
                    .also { it.setSurfaceProvider(view.surfaceProvider) }

                val imageAnalyzer = ImageAnalysis.Builder()
                    .setResolutionSelector(selector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(cameraExecutor, analyzer) }

                // Sharing the preview's ViewPort gives the analysis frames the same crop as the screen.
                val group = UseCaseGroup.Builder()
                    .addUseCase(preview)
                    .addUseCase(imageAnalyzer)
                    .apply { view.viewPort?.let { setViewPort(it) } }
                    .build()

                try {
                    provider.unbindAll()
                    camera = provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        group
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        onDispose { }
    }

    LaunchedEffect(camera, isFlashEnabled) {
        camera?.cameraControl?.enableTorch(isFlashEnabled)
    }

    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                onPreviewReady(this)
                previewView = this
            }
        },
        modifier = modifier.fillMaxSize()
    )
}
