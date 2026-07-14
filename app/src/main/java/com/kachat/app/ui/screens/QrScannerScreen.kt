package com.kachat.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.Result
import com.google.zxing.ResultMetadataType
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.util.QrFrameChunker
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-screen camera QR scanner. Decodes with the same ZXing core library already
 * used to render QR codes elsewhere in the app — no separate scanning library needed.
 */
@Composable
fun QrScannerOverlay(onScanned: (String) -> Unit, onDismiss: () -> Unit) {
    val hasScanned = remember { AtomicBoolean(false) }
    ScannerScaffold(onDismiss = onDismiss) {
        CameraQrScanner(modifier = Modifier.fillMaxSize()) { result ->
            if (hasScanned.compareAndSet(false, true)) onScanned(result.text)
        }
    }
}

/**
 * Scans a KasSigner animated multi-frame QR sequence (a signed KSPT response, or anything else
 * chunked with [QrFrameChunker]) and reassembles it. Deliberately a contained inline card, not a
 * full-screen takeover — matches KasSigner's own web companion (KasSee), whose scanner is a fixed
 * ~300dp square sitting in the normal page flow with a title/progress-dots/cancel link around it,
 * not an edge-to-edge camera view. Unlike [QrScannerOverlay], which fires once on the first
 * decode, this keeps scanning until every frame has been seen.
 */
@Composable
fun MultiFrameQrScannerOverlay(
    isComplete: (ByteArray) -> Boolean,
    onComplete: (ByteArray) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val accumulator = remember { QrFrameChunker.Accumulator(isComplete) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var receivedIndices by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var done by remember { mutableStateOf(false) }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text("Scan Signed Transaction", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(12.dp))

        Box(
            modifier = Modifier
                .size(280.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black)
                .border(1.dp, Color(0xFF2C2C2E), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (hasCameraPermission) {
                CameraQrScanner(modifier = Modifier.fillMaxSize()) { result ->
                    if (done) return@CameraQrScanner
                    val bytes = extractRawBytes(result) ?: return@CameraQrScanner
                    val complete = accumulator.addFrame(bytes)
                    progress = accumulator.progress
                    receivedIndices = accumulator.receivedFrameIndices
                    if (complete != null) {
                        done = true
                        onComplete(complete)
                    }
                }

                val infiniteTransition = rememberInfiniteTransition(label = "scanGuide")
                val glowAlpha by infiniteTransition.animateFloat(
                    initialValue = 0.4f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(animation = tween(1000), repeatMode = RepeatMode.Reverse),
                    label = "glowAlpha"
                )
                Box(
                    modifier = Modifier
                        .size(190.dp)
                        .border(2.dp, KaspaTeal.copy(alpha = glowAlpha), RoundedCornerShape(8.dp))
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Camera permission is needed to scan a QR code",
                        color = Color.Gray,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("Grant Permission", color = KaspaTeal)
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        val (received, total) = progress ?: (0 to 0)
        if (total > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(total) { i ->
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (i in receivedIndices) KaspaTeal else Color(0xFF2C2C2E))
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("$received / $total frames", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        } else {
            Text("Waiting for camera...", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onCancel) {
            Text("Cancel", color = Color.Gray)
        }
    }
}

/** Camera permission handling + framing-guide chrome + close button, shared by both scanner variants above. */
@Composable
private fun ScannerScaffold(onDismiss: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (hasCameraPermission) {
            content()

            // Framing guide, matching a typical QR scanner UI.
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(250.dp)
                    .border(2.dp, Color.White, RoundedCornerShape(16.dp))
            )
        } else {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Camera permission is needed to scan a QR code",
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("Grant permission")
                }
            }
        }

        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.TopStart)
                .zIndex(1f)
                .statusBarsPadding()
                .padding(16.dp)
                .size(40.dp)
                .background(Color(0x66000000), CircleShape)
        ) {
            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
        }
    }
}

/**
 * CameraX preview bound to a ZXing decode analyzer — [onResult] fires for every decoded frame, as
 * often as the camera delivers one. Requests a higher analysis resolution than CameraX's default
 * (which favors speed over detail) and supports pinch-to-zoom, since a weak/budget camera often
 * just can't resolve a small or distant QR's fine modules at the default settings — this matters
 * most when scanning a QR displayed on another device's screen (KasSigner), whose code density is
 * fixed by its own firmware and isn't something this app can simplify.
 */
@Composable
private fun CameraQrScanner(modifier: Modifier = Modifier, onResult: (Result) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var camera by remember { mutableStateOf<Camera?>(null) }
    var zoomRatio by remember { mutableStateOf(1f) }

    AndroidView(
        modifier = modifier.pointerInput(Unit) {
            detectTransformGestures { _, _, zoomChange, _ ->
                val cam = camera ?: return@detectTransformGestures
                val state = cam.cameraInfo.zoomState.value ?: return@detectTransformGestures
                zoomRatio = (zoomRatio * zoomChange).coerceIn(state.minZoomRatio, state.maxZoomRatio)
                cam.cameraControl.setZoomRatio(zoomRatio)
            }
        },
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                // SurfaceView (the default) has its own window surface that can
                // intercept touches meant for sibling Compose overlays like the
                // close button, even when it's declared on top — TextureView
                // composes normally within the view hierarchy's touch dispatch.
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                // CameraX's default ImageAnalysis resolution is tuned for throughput, not detail
                // (often ~640x480 on budget hardware) — that's too coarse for a dense QR's modules
                // to survive the analysis downscale, even when the on-screen preview looks sharp.
                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                    )
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(resolutionSelector)
                    .build()
                analysis.setAnalyzer(Executors.newSingleThreadExecutor()) { imageProxy ->
                    decodeQrCode(imageProxy, onResult)
                }
                try {
                    cameraProvider.unbindAll()
                    camera = cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis
                    )
                } catch (e: Exception) {
                    // Nothing to scan if the camera fails to bind — user can dismiss and retry.
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        }
    )
}

/**
 * Recovers the exact original bytes from a decoded byte-mode QR (a KSPT frame), trying a few
 * fallbacks since ZXing's [Result.getRawBytes] is documented as "if applicable" and isn't
 * reliably populated for every QR byte-mode result:
 * 1. [ResultMetadataType.BYTE_SEGMENTS] — the raw per-segment byte data ZXing decoded, before
 *    any charset interpretation; the most direct source when present.
 * 2. [Result.getRawBytes] itself.
 * 3. Re-encoding [Result.getText] as ISO-8859-1 — since the encoder side
 * ([com.kachat.app.ui.screens.rememberQrBitmapPainter]'s ByteArray overload, and KasSigner's own
 * device firmware) both produce byte-mode QR content that ZXing decodes into a `String` via
 * ISO-8859-1 by default, re-encoding that string as ISO-8859-1 round-trips back to the exact
 * original bytes (1 byte : 1 char : 1 byte).
 */
private fun extractRawBytes(result: Result): ByteArray? {
    @Suppress("UNCHECKED_CAST")
    val segments = result.resultMetadata?.get(ResultMetadataType.BYTE_SEGMENTS) as? List<ByteArray>
    if (!segments.isNullOrEmpty()) {
        return segments.reduce { acc, segment -> acc + segment }
    }
    result.rawBytes?.let { if (it.isNotEmpty()) return it }
    return result.text?.toByteArray(Charsets.ISO_8859_1)
}

// Restricting to QR (this app never scans anything else) both speeds up MultiFormatReader's
// attempt per frame and avoids it occasionally matching a stray non-QR pattern in the frame.
private val qrOnlyHints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))

private fun decodeQrCode(imageProxy: ImageProxy, onResult: (Result) -> Unit) {
    try {
        val buffer = imageProxy.planes[0].buffer
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        val source = PlanarYUVLuminanceSource(
            data, imageProxy.width, imageProxy.height, 0, 0, imageProxy.width, imageProxy.height, false
        )
        val result = try {
            MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), qrOnlyHints)
        } catch (e: NotFoundException) {
            // HybridBinarizer's local-contrast threshold can miss a code under uneven lighting
            // (e.g. reading a phone/device screen's own backlight glare) that a simpler global
            // threshold catches — worth one more attempt on the same frame before giving up on it.
            MultiFormatReader().decode(BinaryBitmap(GlobalHistogramBinarizer(source)), qrOnlyHints)
        }
        onResult(result)
    } catch (e: NotFoundException) {
        // No QR code in this frame — expected for most frames, just wait for the next one.
    } catch (e: Exception) {
        // Decode failure for this frame — ignore and try the next one.
    } finally {
        imageProxy.close()
    }
}
