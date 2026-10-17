package com.golftracker.app.ui.components

import android.hardware.camera2.CameraCharacteristics
import android.util.Range
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * The live camera: the preview, frames for [analyzer], and video for shot recordings. Recording at
 * 60 fps is tried first, then the camera's usual rate, then no video at all if the phone can't record
 * alongside the analysis; [onVideoCapture] gets the video use case, or null.
 */
@Composable
fun CameraPreviewView(
    isFlashEnabled: Boolean,
    analyzer: ImageAnalysis.Analyzer,
    onPreviewReady: (PreviewView) -> Unit,
    /** The camera's field of view across the screen's height, in radians, once known. */
    onFieldOfView: (Double) -> Unit = {},
    onVideoCapture: (VideoCapture<Recorder>?) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }

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

                fun videoCapture(fps: Int?): VideoCapture<Recorder> {
                    val recorder = Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD)))
                        .build()
                    return VideoCapture.Builder(recorder)
                        .apply { if (fps != null) setTargetFrameRate(Range(fps, fps)) }
                        .build()
                }

                // Sharing the preview's ViewPort gives the analysis frames the same crop as the screen.
                fun bind(video: VideoCapture<Recorder>?): Camera {
                    val group = UseCaseGroup.Builder()
                        .addUseCase(preview)
                        .addUseCase(imageAnalyzer)
                        .apply { if (video != null) addUseCase(video) }
                        .apply { view.viewPort?.let { setViewPort(it) } }
                        .build()
                    provider.unbindAll()
                    return provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, group)
                }

                for (fps in listOf(60, null, -1)) {
                    val video = if (fps == -1) null else videoCapture(fps)
                    try {
                        camera = bind(video)
                        onVideoCapture(video)
                        camera?.let { verticalFieldOfView(it)?.let(onFieldOfView) }
                        break
                    } catch (e: Exception) {
                        android.util.Log.w("CameraPreviewView", "Couldn't bind camera with video at ${fps ?: "default"} fps", e)
                    }
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

/**
 * Field of view across the screen's height with the phone upright. The 16:9 stream keeps the
 * sensor's full long side, which runs along the screen's height, and the screen's narrower shape is
 * cropped from the sides, so this is the angle the sensor's long side covers.
 */
@OptIn(ExperimentalCamera2Interop::class)
private fun verticalFieldOfView(camera: Camera): Double? = try {
    val info = Camera2CameraInfo.from(camera.cameraInfo)
    val focal = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
    val sensor = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
    if (focal == null || sensor == null || focal <= 0f) null
    else 2 * kotlin.math.atan(maxOf(sensor.width, sensor.height) / (2.0 * focal))
} catch (e: Exception) {
    null
}
