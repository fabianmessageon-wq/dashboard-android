package dev.jaredhq.dashboardandroid.ui.camera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.jaredhq.dashboardandroid.di.ServiceLocator
import dev.jaredhq.dashboardandroid.watch.engine.WatchControlEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Remote-shutter camera (watch-triggered photos). Opened either from the watch's camera screen
 * (CAMERA_OPEN control event → WatchConnectionService) or in-app; while it is open the watch is
 * held in remote-shutter mode ([enterCameraMode][dev.jaredhq.dashboardandroid.watch.engine.WatchEngine.enterCameraMode]),
 * so a wrist tap arrives as CAMERA_TAKE_PHOTO and fires a 3s countdown capture, matching the
 * VeryFit flow. Photos land in MediaStore under Pictures/Dashboard. CAMERA_CLOSE (leaving the
 * watch screen) finishes the activity.
 *
 * V1 is capture-only — live preview streaming back to the watch face
 * (REQUEST_START_CAMERA_PREVIEW etc.) is the documented stretch goal and is not handled here.
 */
class RemoteCameraActivity : ComponentActivity() {

    private var imageCapture: ImageCapture? = null
    private var cameraGranted = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                cameraGranted = true
                recreate() // simplest way to rebuild the preview with the grant in place
            } else {
                Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ServiceLocator.init(applicationContext)

        cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (!cameraGranted) permissionLauncher.launch(Manifest.permission.CAMERA)

        // React to wrist taps while this screen is open.
        lifecycleScope.launch {
            ServiceLocator.watchEngine.controlEvents.collect { event ->
                when (event) {
                    WatchControlEvent.CAMERA_TAKE_PHOTO -> startCountdownCapture()
                    WatchControlEvent.CAMERA_CLOSE -> finish()
                    else -> Unit
                }
            }
        }

        setContent {
            MaterialTheme {
                CameraScreen(
                    onShutter = { startCountdownCapture() },
                    onClose = { finish() },
                    onSwitchCamera = { useFrontCamera = !useFrontCamera; rebindRequested?.invoke() },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Hold the watch in remote-shutter mode while this screen is visible.
        runCatching { ServiceLocator.watchEngine.enterCameraMode() }
    }

    override fun onStop() {
        runCatching { ServiceLocator.watchEngine.exitCameraMode() }
        super.onStop()
    }

    // ── capture ────────────────────────────────────────────────────────────────────────

    private var useFrontCamera = false
    private var rebindRequested: (() -> Unit)? = null
    private var countdownState: ((Int) -> Unit)? = null
    private var countdownRunning = false

    private fun startCountdownCapture() {
        if (countdownRunning || !cameraGranted) return
        countdownRunning = true
        lifecycleScope.launch {
            for (i in COUNTDOWN_SECONDS downTo 1) {
                countdownState?.invoke(i)
                delay(1_000)
            }
            countdownState?.invoke(0)
            takePhoto()
            countdownRunning = false
        }
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val name = "watch-" +
            SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(System.currentTimeMillis())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Dashboard")
            }
        }
        val options = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values,
        ).build()
        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Toast.makeText(this@RemoteCameraActivity, "Photo saved", Toast.LENGTH_SHORT).show()
                }

                override fun onError(e: ImageCaptureException) {
                    Log.w(TAG, "capture failed", e)
                    Toast.makeText(this@RemoteCameraActivity, "Capture failed", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    // ── UI ─────────────────────────────────────────────────────────────────────────────

    @androidx.compose.runtime.Composable
    private fun CameraScreen(
        onShutter: () -> Unit,
        onClose: () -> Unit,
        onSwitchCamera: () -> Unit,
    ) {
        val context = LocalContext.current
        var countdown by remember { mutableIntStateOf(0) }
        var rebindTick by remember { mutableStateOf(0) }
        countdownState = { countdown = it }
        rebindRequested = { rebindTick++ }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (cameraGranted) {
                val lastBoundTick = remember { mutableIntStateOf(-1) }
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx -> PreviewView(ctx) },
                    update = { view ->
                        // Bind once after creation, then only when the lens is switched.
                        if (lastBoundTick.intValue != rebindTick) {
                            lastBoundTick.intValue = rebindTick
                            bindCamera(view)
                        }
                    },
                )
            } else {
                Text(
                    "Waiting for camera permission…",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            if (countdown > 0) {
                Text(
                    text = countdown.toString(),
                    color = Color.White,
                    fontSize = 96.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopStart).padding(16.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }
            IconButton(
                onClick = onSwitchCamera,
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
            ) {
                Icon(Icons.Default.Cameraswitch, contentDescription = "Switch camera", tint = Color.White)
            }

            // Shutter: plain ringed circle, tap to start the same countdown the wrist tap uses.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 40.dp)
                    .size(72.dp)
                    .background(Color.White, CircleShape)
                    .padding(6.dp)
                    .background(Color.Black, CircleShape)
                    .padding(4.dp)
                    .background(Color.White, CircleShape)
                    .clickable(onClick = onShutter),
            )
        }
    }

    private fun bindCamera(view: PreviewView) {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            runCatching {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(view.surfaceProvider)
                }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    capture,
                )
                imageCapture = capture
            }.onFailure { Log.w(TAG, "camera bind failed", it) }
        }, ContextCompat.getMainExecutor(this))
    }

    companion object {
        private const val TAG = "RemoteCamera"
        private const val COUNTDOWN_SECONDS = 3
    }
}
