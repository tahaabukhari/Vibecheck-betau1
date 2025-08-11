package com.example.vibecheck

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.*
import android.media.Image
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var btnCapture: Button
    private lateinit var btnSwitchCamera: Button
    private lateinit var graphicOverlay: GraphicOverlay
    private lateinit var tvFps: TextView
    private lateinit var cameraBar: ConstraintLayout

    private var imageCapture: ImageCapture? = null
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

    private lateinit var autoSnapManager: AutoSnapManager

    private val cameraPermission = Manifest.permission.CAMERA
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
        }
    }

    // For FPS calculation
    private var lastFpsTimestamp = 0L
    private var framesInThisSecond = 0
    private var fps = 0

    // For throttling model inference (optional)
    private var frameCount = 0
    private val inferenceInterval = 1 // Apply model on every frame

    // --- For delayed main vibe switching ---
    private var lastVibe: String = "Unknown"
    private var lastVibeSwitchTime: Long = 0L
    private var candidateVibe: String = "Unknown"
    private val vibeSwitchDelayMs = 1000L // 1 second

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        btnCapture = findViewById(R.id.btnCapture)
        btnSwitchCamera = findViewById(R.id.btnSwitchCamera)
        graphicOverlay = findViewById(R.id.graphicOverlay)
        tvFps = findViewById(R.id.tvFps)
        cameraBar = findViewById(R.id.cameraBar)
        autoSnapManager = AutoSnapManager(this)

        btnCapture.setOnClickListener { takePhoto() }
        btnSwitchCamera.setOnClickListener { toggleCamera() }

        // --- LOCK CAMERA BUTTONS ORIENTATION ---
        val lockPortraitButtons = {
            cameraBar.rotation = 0f
            btnCapture.rotation = 0f
            btnSwitchCamera.rotation = 0f
        }
        lockPortraitButtons()
        val orientationListener = object : android.view.OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                lockPortraitButtons()
            }
        }
        orientationListener.enable()

        if (ContextCompat.checkSelfPermission(this, cameraPermission) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(cameraPermission)
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        cameraBar.rotation = 0f
        btnCapture.rotation = 0f
        btnSwitchCamera.rotation = 0f
    }

    private fun toggleCamera() {
        cameraSelector =
            if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA)
                CameraSelector.DEFAULT_FRONT_CAMERA
            else
                CameraSelector.DEFAULT_BACK_CAMERA
        startCamera()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder().build()

            // FAST FaceDetectorOptions (only detection, no landmarks/classification/contour)
            val realTimeOpts = FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                .build()

            val faceDetector = FaceDetection.getClient(realTimeOpts)

            val analyzerExecutor = Executors.newSingleThreadExecutor()
            val imageAnalyzer = ImageAnalysis.Builder()
                .setTargetResolution(Size(320, 240)) // Lower resolution for higher FPS
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also {
                    it.setAnalyzer(analyzerExecutor) { imageProxy ->
                        processImageProxy(faceDetector, imageProxy)
                    }
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture, imageAnalyzer
                )
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to start camera", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun processImageProxy(
        detector: FaceDetector,
        imageProxy: ImageProxy
    ) {
        // FPS calculation
        val now = System.currentTimeMillis()
        if (lastFpsTimestamp == 0L) {
            lastFpsTimestamp = now
        }
        framesInThisSecond++
        if (now - lastFpsTimestamp >= 1000) {
            fps = framesInThisSecond
            framesInThisSecond = 0
            lastFpsTimestamp = now
            runOnUiThread {
                tvFps.text = "FPS: $fps"
            }
        }

        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val image = InputImage.fromMediaImage(mediaImage, rotationDegrees)

            detector.process(image)
                .addOnSuccessListener { faces: List<Face> ->
                    graphicOverlay.clear()
                    graphicOverlay.setImageSourceInfo(
                        image.width,
                        image.height,
                        cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA
                    )
                    frameCount++

                    // Vibe match logic: collect the main vibes for all faces
                    val vibesThisFrame = mutableListOf<String>()

                    for (face in faces) {
                        val faceBitmap = try {
                            cropFaceFromImage(mediaImage, face.boundingBox, rotationDegrees)
                        } catch (e: Exception) {
                            null
                        }
                        if (faceBitmap != null) {
                            if (frameCount % inferenceInterval == 0) {
                                val vibeClassifier = VibeClassifier(this)
                                val newCandidateVibe = vibeClassifier.classifyEmotion(faceBitmap)
                                vibesThisFrame.add(newCandidateVibe)
                                val nowVibe = System.currentTimeMillis()

                                // Delayed main vibe logic (per face, but global for simplicity)
                                if (newCandidateVibe != candidateVibe) {
                                    candidateVibe = newCandidateVibe
                                    lastVibeSwitchTime = nowVibe
                                }

                                if (
                                    candidateVibe != lastVibe &&
                                    (nowVibe - lastVibeSwitchTime) > vibeSwitchDelayMs &&
                                    candidateVibe != "neutral" && candidateVibe != "Unknown"
                                ) {
                                    lastVibe = candidateVibe
                                } else if (candidateVibe == "neutral" || candidateVibe == "Unknown") {
                                    lastVibe = candidateVibe
                                    lastVibeSwitchTime = nowVibe
                                }

                                val topEmotions = vibeClassifier.getTop3Emotions(faceBitmap)
                                graphicOverlay.add(
                                    FaceGraphic(
                                        graphicOverlay,
                                        this@MainActivity,
                                        face,
                                        faceBitmap,
                                        mainVibe = lastVibe,
                                        topEmotions = topEmotions
                                    )
                                )
                            }
                        }
                    }

                    // --- Use AutoSnapManager for autosnap logic ---
                    if (autoSnapManager.shouldAutoSnap(vibesThisFrame)) {
                        runOnUiThread {
                            safelyTakePhotoWithToast()
                        }
                    }
                }
                .addOnFailureListener { e ->
                    e.printStackTrace()
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }

    private fun safelyTakePhotoWithToast() {
        autoSnapManager.onSnapStarted()
        try {
            takePhotoWithCallback {
                // Only show Toast if activity is not finishing and context is valid
                if (!isFinishing && !isDestroyed) {
                    autoSnapManager.onSnapCompleted()
                } else {
                    autoSnapManager.onSnapFailed("Activity not active")
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
            autoSnapManager.onSnapFailed("Exception: ${ex.localizedMessage}")
        }
    }

    private fun takePhotoWithCallback(onComplete: () -> Unit) {
        val imageCapture = imageCapture ?: run { onComplete(); return }
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/VibeCheck")
            }
        }
        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()
        imageCapture.takePicture(
            outputOptions, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    try {
                        Toast.makeText(applicationContext, "Photo saved to gallery!", Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) {}
                    onComplete()
                }

                override fun onError(exc: ImageCaptureException) {
                    try {
                        Toast.makeText(applicationContext, "Failed to save photo: ${exc.message}", Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) {}
                    onComplete()
                }
            }
        )
    }

    // Utility to crop face bitmap from Image (YUV_420_888)
    private fun cropFaceFromImage(image: Image, boundingBox: Rect, rotationDegrees: Int): Bitmap? {
        val nv21 = yuv420ToNV21(image)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 100, out)
        val yuvBytes = out.toByteArray()
        var bitmap = BitmapFactory.decodeByteArray(yuvBytes, 0, yuvBytes.size) ?: return null

        if (rotationDegrees != 0) {
            val matrix = Matrix()
            matrix.postRotate(rotationDegrees.toFloat())
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }

        val left = boundingBox.left.coerceAtLeast(0)
        val top = boundingBox.top.coerceAtLeast(0)
        val right = boundingBox.right.coerceAtMost(bitmap.width)
        val bottom = boundingBox.bottom.coerceAtMost(bitmap.height)
        if (right <= left || bottom <= top) return null

        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }

    private fun yuv420ToNV21(image: Image): ByteArray {
        val ySize = image.planes[0].buffer.remaining()
        val uSize = image.planes[1].buffer.remaining()
        val vSize = image.planes[2].buffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)

        image.planes[0].buffer.get(nv21, 0, ySize)
        image.planes[2].buffer.get(nv21, ySize, vSize)
        image.planes[1].buffer.get(nv21, ySize + vSize, uSize)
        return nv21
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/VibeCheck")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        imageCapture.takePicture(
            outputOptions, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Toast.makeText(applicationContext, "Photo saved to gallery!", Toast.LENGTH_SHORT).show()
                }

                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(applicationContext, "Failed to save photo: ${exc.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}