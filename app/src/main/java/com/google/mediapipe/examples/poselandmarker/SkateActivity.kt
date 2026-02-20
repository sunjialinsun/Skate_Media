package com.google.mediapipe.examples.poselandmarker

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.ScaleGestureDetector
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.PendingRecording
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.camera.view.PreviewView
import com.google.mediapipe.examples.poselandmarker.databinding.ActivitySkateBinding
import com.google.mediapipe.tasks.vision.core.RunningMode
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max

class SkateActivity : AppCompatActivity(), PoseLandmarkerHelper.LandmarkerListener {

    private lateinit var binding: ActivitySkateBinding
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var isRecordingVideo: Boolean = false
    private var currentVideoUri: String? = null
    private var running = false

    private lateinit var poseLandmarkerHelper: PoseLandmarkerHelper
    private var lastFpsUpdateTime = 0L
    private var fpsSmoothed = 0.0

    private var lastTimestampMs: Long? = null
    private var lastAngleDeg: Double? = null
    private var angleUnwrappedDeg: Double = 0.0
    private var groundAnkleY: Float? = null
    private var inAir: Boolean = false
    private var jumpStartTimeMs: Long = 0L
    private var jumpStartAngleUnwrappedDeg: Double = 0.0
    private var jumpMinAnkleY: Float = 0f
    private var jumpStartHipX: Float = 0f
    private var jumpStartHipY: Float = 0f
    private val jumpEvents = mutableListOf<JumpEvent>()
    private val recordedFrames = mutableListOf<FrameFeature>()

    private val assumedBodyHeightCm = 160.0
    private val frameWindow = ArrayDeque<FrameFeature>()
    private val windowSize = 5

    private val toePickRangeThreshold = 0.06f
    private val hipOverFootThreshold = 0.03f
    private val kneeBentAngle = 140.0
    private val kneeBentAngleTakeoff = 120.0
    private val kneeExtendedAngle = 150.0
    private val edgeOffsetSmall = 0.02f
    private val edgeOffsetMedium = 0.05f
    private val edgeOffsetLarge = 0.1f
    private val ankleSeparationLarge = 0.15f
    private val torsoTiltSmall = 0.05f
    private val torsoTiltLarge = 0.1f
    private val jumpTakeoffThreshold = 0.05f
    private val jumpLandingThreshold = 0.02f

    private var currentMode: CaptureMode = CaptureMode.JUMP

    private val cameraPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                initPoseLandmarker()
                startCamera()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySkateBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        binding.viewFinder.implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        binding.viewFinder.scaleType = PreviewView.ScaleType.FILL_START

        binding.navRecord.setOnClickListener {
            binding.navRecordLabel.setTextColor(
                ContextCompat.getColor(this, R.color.ios_text_primary)
            )
            binding.navJumpsLabel.setTextColor(
                ContextCompat.getColor(this, R.color.ios_text_secondary)
            )
        }

        binding.navJumps.setOnClickListener {
            binding.navRecordLabel.setTextColor(
                ContextCompat.getColor(this, R.color.ios_text_secondary)
            )
            binding.navJumpsLabel.setTextColor(
                ContextCompat.getColor(this, R.color.ios_text_primary)
            )
            val intent = Intent(this, GalleryActivity::class.java)
            intent.putExtra("mode", currentMode.name)
            startActivity(intent)
        }

        binding.tabJump.setOnClickListener {
            currentMode = CaptureMode.JUMP
            binding.tabJump.setBackgroundResource(R.drawable.bg_toggle_mode_selected)
            binding.tabSpin.setBackgroundResource(0)
            binding.navJumpsLabel.text = "Jumps"
        }

        binding.tabSpin.setOnClickListener {
            currentMode = CaptureMode.SPIN
            binding.tabSpin.setBackgroundResource(R.drawable.bg_toggle_mode_selected)
            binding.tabJump.setBackgroundResource(0)
            binding.navJumpsLabel.text = "Spins"
        }

        binding.btnRecord.setOnClickListener {
            if (!isRecordingVideo) {
                isRecordingVideo = true
                updateRecordButton()
                startRecording()
            } else {
                stopRecording()
                isRecordingVideo = false
                updateRecordButton()
            }
        }

        val scaleGestureDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val cameraLocal = camera ?: return false
                    val zoomState = cameraLocal.cameraInfo.zoomState.value ?: return false
                    val currentZoom = zoomState.zoomRatio
                    val minZoom = zoomState.minZoomRatio
                    val maxZoom = zoomState.maxZoomRatio
                    val scale = detector.scaleFactor
                    val newZoom = (currentZoom * scale).coerceIn(minZoom, maxZoom)
                    cameraLocal.cameraControl.setZoomRatio(newZoom)
                    return true
                }
            }
        )
        binding.viewFinder.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            true
        }

        binding.tabJump.performClick()
        binding.navRecord.performClick()
        updateRecordButton()
        requestCameraAndStart()
    }

    private fun requestCameraAndStart() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            initPoseLandmarker()
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun initPoseLandmarker() {
        poseLandmarkerHelper = PoseLandmarkerHelper(
            context = this,
            currentModel = PoseLandmarkerHelper.MODEL_POSE_LANDMARKER_LITE,
            runningMode = RunningMode.LIVE_STREAM,
            poseLandmarkerHelperListener = this
        )
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener(
            {
                cameraProvider = cameraProviderFuture.get()
                bindUseCases()
                running = true
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    private fun bindUseCases() {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        videoCapture = null

        val rotation = binding.viewFinder.display.rotation

        val preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(rotation)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    analyzeImage(imageProxy)
                }
            }

        val cameraSelector =
            CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build()

        camera = provider.bindToLifecycle(
            this,
            cameraSelector,
            preview,
            imageAnalyzer
        )

        preview.setSurfaceProvider(binding.viewFinder.surfaceProvider)
    }

    private fun startRecording() {
        val videoCapture = videoCapture ?: return

        val namePrefix = if (currentMode == CaptureMode.JUMP) "jump_" else "spin_"
        val name = namePrefix + System.currentTimeMillis()

        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/SkateMedia")
            }
        }

        val collection =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }

        val outputOptions = MediaStoreOutputOptions.Builder(
            contentResolver,
            collection
        ).setContentValues(contentValues).build()

        activeRecording =
            videoCapture.output
                .prepareRecording(this, outputOptions)
                .start(ContextCompat.getMainExecutor(this)) { event ->
                    if (event is VideoRecordEvent.Finalize) {
                        activeRecording = null
                        currentVideoUri = event.outputResults.outputUri.toString()
                        updateRecordButton()
                    }
                }

        updateRecordButton()
    }

    private fun stopRecording() {
        activeRecording?.stop()
        activeRecording = null
        updateRecordButton()
        showJumpAnalysis()
    }

    private fun updateRecordButton() {
        if (isRecordingVideo) {
            val inner = binding.recordInner
            inner.animate().scaleX(0.7f).scaleY(0.7f).setDuration(150).start()
            inner.background = ContextCompat.getDrawable(this, R.drawable.bg_record_square)
        } else {
            val inner = binding.recordInner
            inner.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
            inner.background = ContextCompat.getDrawable(this, R.drawable.bg_record_circle)
        }
    }

    private fun analyzeImage(imageProxy: ImageProxy) {
        if (!this::poseLandmarkerHelper.isInitialized) {
            imageProxy.close()
            return
        }
        poseLandmarkerHelper.detectLiveStream(
            imageProxy = imageProxy,
            isFrontCamera = false
        )
    }

    private fun stopAnalysis() {
        running = false
        cameraProvider?.unbindAll()
        imageAnalyzer?.clearAnalyzer()
        binding.tvFps.text = "FPS 0.0"
        if (this::poseLandmarkerHelper.isInitialized) {
            cameraExecutor.execute { poseLandmarkerHelper.clearPoseLandmarker() }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        if (this::poseLandmarkerHelper.isInitialized) {
            cameraExecutor.execute { poseLandmarkerHelper.clearPoseLandmarker() }
        }
    }

    override fun onError(error: String, errorCode: Int) {
        runOnUiThread { }
    }

    override fun onResults(resultBundle: PoseLandmarkerHelper.ResultBundle) {
        if (resultBundle.results.isEmpty()) return
        val result = resultBundle.results[0]

        val instantFps =
            if (resultBundle.inferenceTime > 0) 1000.0 / resultBundle.inferenceTime.toDouble()
            else 0.0
        fpsSmoothed =
            if (fpsSmoothed == 0.0) instantFps else fpsSmoothed * 0.8 + instantFps * 0.2

        if (isRecordingVideo) {
            val landmarks = result.landmarks().firstOrNull()
            if (landmarks != null && landmarks.size > 28) {
                val timestampMs = System.currentTimeMillis()
                val leftHip = Joint(landmarks[23].x(), landmarks[23].y())
                val rightHip = Joint(landmarks[24].x(), landmarks[24].y())
                val leftKnee = Joint(landmarks[25].x(), landmarks[25].y())
                val rightKnee = Joint(landmarks[26].x(), landmarks[26].y())
                val leftAnkle = Joint(landmarks[27].x(), landmarks[27].y())
                val rightAnkle = Joint(landmarks[28].x(), landmarks[28].y())
                val leftShoulder = Joint(landmarks[11].x(), landmarks[11].y())
                val rightShoulder = Joint(landmarks[12].x(), landmarks[12].y())

                val frame = FrameFeature(
                    timestampMs = timestampMs,
                    leftHip = leftHip,
                    rightHip = rightHip,
                    leftKnee = leftKnee,
                    rightKnee = rightKnee,
                    leftAnkle = leftAnkle,
                    rightAnkle = rightAnkle,
                    leftShoulder = leftShoulder,
                    rightShoulder = rightShoulder
                )
                recordedFrames.add(frame)
            }
        }

        runOnUiThread {
            binding.overlayView.setResults(
                result,
                resultBundle.inputImageHeight,
                resultBundle.inputImageWidth,
                RunningMode.LIVE_STREAM
            )
            val text = "FPS ${"%.1f".format(fpsSmoothed)}"
            binding.tvFps.text = text
        }
    }

    private fun processJumpFrame(resultBundle: PoseLandmarkerHelper.ResultBundle) {
        val result = resultBundle.results[0]
        val landmarks = result.landmarks().firstOrNull() ?: return
        if (landmarks.size <= 28) return

        val timestampMs = System.currentTimeMillis()

        val leftHip = Joint(landmarks[23].x(), landmarks[23].y())
        val rightHip = Joint(landmarks[24].x(), landmarks[24].y())
        val leftKnee = Joint(landmarks[25].x(), landmarks[25].y())
        val rightKnee = Joint(landmarks[26].x(), landmarks[26].y())
        val leftAnkle = Joint(landmarks[27].x(), landmarks[27].y())
        val rightAnkle = Joint(landmarks[28].x(), landmarks[28].y())
        val leftShoulder = Joint(landmarks[11].x(), landmarks[11].y())
        val rightShoulder = Joint(landmarks[12].x(), landmarks[12].y())

        val frame = FrameFeature(
            timestampMs = timestampMs,
            leftHip = leftHip,
            rightHip = rightHip,
            leftKnee = leftKnee,
            rightKnee = rightKnee,
            leftAnkle = leftAnkle,
            rightAnkle = rightAnkle,
            leftShoulder = leftShoulder,
            rightShoulder = rightShoulder
        )

        frameWindow.addLast(frame)
        if (frameWindow.size > windowSize) {
            frameWindow.removeFirst()
        }

        val hipCenter = frame.hipCenter()
        val shoulderCenter = frame.shoulderCenter()
        val dx = shoulderCenter.x - hipCenter.x
        val dy = shoulderCenter.y - hipCenter.y
        val angleRad = atan2(dy.toDouble(), dx.toDouble())
        val angleDeg = Math.toDegrees(angleRad)

        val lastAngle = lastAngleDeg
        if (lastAngle != null) {
            var delta = angleDeg - lastAngle
            if (delta > 180) delta -= 360.0
            if (delta < -180) delta += 360.0
            angleUnwrappedDeg += delta
        }
        lastAngleDeg = angleDeg

        val minAnkleY = minOf(leftAnkle.y, rightAnkle.y)
        if (groundAnkleY == null) {
            groundAnkleY = minAnkleY
        }
        val ground = groundAnkleY!!

        if (!inAir) {
            groundAnkleY = ground * 0.9f + minAnkleY * 0.1f
            if (ground - minAnkleY > jumpTakeoffThreshold) {
                inAir = true
                jumpStartTimeMs = timestampMs
                jumpStartAngleUnwrappedDeg = angleUnwrappedDeg
                jumpMinAnkleY = minAnkleY
                jumpStartHipX = hipCenter.x
                jumpStartHipY = hipCenter.y
            }
        } else {
            if (minAnkleY < jumpMinAnkleY) {
                jumpMinAnkleY = minAnkleY
            }
            if (ground - minAnkleY < jumpLandingThreshold) {
                val airtimeMs = timestampMs - jumpStartTimeMs
                val verticalNorm = ground - jumpMinAnkleY
                val heightCm = (verticalNorm * assumedBodyHeightCm).coerceAtLeast(0.0)
                val rotationDeg = angleUnwrappedDeg - jumpStartAngleUnwrappedDeg
                val rotationTurns = rotationDeg / 360.0
                val type = classifyJumpType()
                val label = when (type) {
                    JumpType.AXEL -> "Axel"
                    JumpType.SALCHOW -> "Salchow"
                    JumpType.LOOP -> "Loop"
                    JumpType.FLIP -> "Flip"
                    JumpType.LUTZ -> "Lutz"
                    JumpType.TOE_LOOP -> "Toe loop"
                    JumpType.UNKNOWN -> "Unknown"
                }
                jumpEvents.add(
                    JumpEvent(
                        timestampMs = timestampMs,
                        heightCm = heightCm,
                        airtimeMs = airtimeMs,
                        rotationDeg = rotationDeg,
                        rotationTurns = rotationTurns,
                        label = label,
                        type = type
                    )
                )
                inAir = false
            }
        }
    }

    private fun showJumpAnalysis() {
        if (recordedFrames.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("跳跃分析")
                .setMessage("本次录制未采集到骨架数据。")
                .setPositiveButton("确定", null)
                .show()
            return
        }

        val progressDialog = AlertDialog.Builder(this)
            .setTitle("跳跃分析")
            .setMessage("分析进行中...")
            .setCancelable(false)
            .create()
        progressDialog.show()

        val sessionId = System.currentTimeMillis()

        cameraExecutor.execute {
            val events = analyzeRecordedFrames()
            saveRecordedFramesToFile(sessionId)

            runOnUiThread {
                progressDialog.dismiss()

                if (events.isEmpty()) {
                    AlertDialog.Builder(this)
                        .setTitle("跳跃分析")
                        .setMessage("本次录制未检测到有效跳跃。")
                        .setPositiveButton("确定", null)
                        .show()
                } else {
                    val results = events.map {
                        JumpResult(
                            heightCm = it.heightCm,
                            airtimeMs = it.airtimeMs,
                            rotationTurns = it.rotationTurns,
                            type = it.type.name,
                            label = it.label
                        )
                    }
                    val session = JumpSession(
                        id = sessionId,
                        videoUri = currentVideoUri,
                        mode = currentMode.name,
                        createdAt = System.currentTimeMillis(),
                        results = results
                    )
                    JumpSessionStore.addSession(this, session)

                    val intent = Intent(this, JumpResultActivity::class.java)
                    intent.putExtra("session_id", sessionId)
                    startActivity(intent)
                }

                recordedFrames.clear()
                jumpEvents.clear()
                frameWindow.clear()
                inAir = false
                groundAnkleY = null
                lastAngleDeg = null
                angleUnwrappedDeg = 0.0
            }
        }
    }

    private enum class CaptureMode {
        JUMP,
        SPIN
    }

    private fun analyzeRecordedFrames(): List<JumpEvent> {
        jumpEvents.clear()
        frameWindow.clear()
        inAir = false
        groundAnkleY = null
        lastAngleDeg = null
        angleUnwrappedDeg = 0.0

        recordedFrames.forEach { frame ->
            frameWindow.addLast(frame)
            if (frameWindow.size > windowSize) {
                frameWindow.removeFirst()
            }

            val hipCenter = frame.hipCenter()
            val shoulderCenter = frame.shoulderCenter()
            val dx = shoulderCenter.x - hipCenter.x
            val dy = shoulderCenter.y - hipCenter.y
            val angleRad = atan2(dy.toDouble(), dx.toDouble())
            val angleDeg = Math.toDegrees(angleRad)

            val lastAngle = lastAngleDeg
            if (lastAngle != null) {
                var delta = angleDeg - lastAngle
                if (delta > 180) delta -= 360.0
                if (delta < -180) delta += 360.0
                angleUnwrappedDeg += delta
            }
            lastAngleDeg = angleDeg

            val leftAnkle = frame.leftAnkle
            val rightAnkle = frame.rightAnkle
            val minAnkleY = minOf(leftAnkle.y, rightAnkle.y)
            if (groundAnkleY == null) {
                groundAnkleY = minAnkleY
            }
            val ground = groundAnkleY!!

            if (!inAir) {
                groundAnkleY = ground * 0.9f + minAnkleY * 0.1f
                if (ground - minAnkleY > jumpTakeoffThreshold) {
                    inAir = true
                    jumpStartTimeMs = frame.timestampMs
                    jumpStartAngleUnwrappedDeg = angleUnwrappedDeg
                    jumpMinAnkleY = minAnkleY
                    jumpStartHipX = hipCenter.x
                    jumpStartHipY = hipCenter.y
                }
            } else {
                if (minAnkleY < jumpMinAnkleY) {
                    jumpMinAnkleY = minAnkleY
                }
                if (ground - minAnkleY < jumpLandingThreshold) {
                    val airtimeMs = frame.timestampMs - jumpStartTimeMs
                    val verticalNorm = ground - jumpMinAnkleY
                    val heightCm = (verticalNorm * assumedBodyHeightCm).coerceAtLeast(0.0)
                    val rotationDeg = angleUnwrappedDeg - jumpStartAngleUnwrappedDeg
                    val rotationTurns = rotationDeg / 360.0
                    val type = classifyJumpType()
                    val label = when (type) {
                        JumpType.AXEL -> "Axel"
                        JumpType.SALCHOW -> "Salchow"
                        JumpType.LOOP -> "Loop"
                        JumpType.FLIP -> "Flip"
                        JumpType.LUTZ -> "Lutz"
                        JumpType.TOE_LOOP -> "Toe loop"
                        JumpType.UNKNOWN -> "Unknown"
                    }
                    jumpEvents.add(
                        JumpEvent(
                            timestampMs = frame.timestampMs,
                            heightCm = heightCm,
                            airtimeMs = airtimeMs,
                            rotationDeg = rotationDeg,
                            rotationTurns = rotationTurns,
                            label = label,
                            type = type
                        )
                    )
                    inAir = false
                }
            }
        }

        return jumpEvents.toList()
    }

    private fun saveRecordedFramesToFile(sessionId: Long) {
        val array = JSONArray()
        recordedFrames.forEach { frame ->
            val obj = JSONObject()
            obj.put("timestampMs", frame.timestampMs)
            obj.put("leftHipX", frame.leftHip.x)
            obj.put("leftHipY", frame.leftHip.y)
            obj.put("rightHipX", frame.rightHip.x)
            obj.put("rightHipY", frame.rightHip.y)
            obj.put("leftKneeX", frame.leftKnee.x)
            obj.put("leftKneeY", frame.leftKnee.y)
            obj.put("rightKneeX", frame.rightKnee.x)
            obj.put("rightKneeY", frame.rightKnee.y)
            obj.put("leftAnkleX", frame.leftAnkle.x)
            obj.put("leftAnkleY", frame.leftAnkle.y)
            obj.put("rightAnkleX", frame.rightAnkle.x)
            obj.put("rightAnkleY", frame.rightAnkle.y)
            obj.put("leftShoulderX", frame.leftShoulder.x)
            obj.put("leftShoulderY", frame.leftShoulder.y)
            obj.put("rightShoulderX", frame.rightShoulder.x)
            obj.put("rightShoulderY", frame.rightShoulder.y)
            array.put(obj)
        }
        val fileName = "pose_$sessionId.json"
        openFileOutput(fileName, MODE_PRIVATE).use { out ->
            out.write(array.toString().toByteArray())
        }
    }

    private fun classifyJumpType(): JumpType {
        if (frameWindow.isEmpty()) return JumpType.UNKNOWN
        val frames = frameWindow.toList()
        val first = frames.first()
        val last = frames.last()
        val hipCenterFirst = first.hipCenter()
        val hipCenterLast = last.hipCenter()
        val shoulderCenterFirst = first.shoulderCenter()
        val dyHip = hipCenterLast.y - hipCenterFirst.y
        val isForward = dyHip < -0.01f && hipCenterLast.y < shoulderCenterFirst.y
        val isBackward = dyHip > 0.01f && hipCenterLast.y > shoulderCenterFirst.y
        val rightEdgeOffset = last.rightAnkle.x - last.rightHip.x
        val leftEdgeOffset = last.leftAnkle.x - last.leftHip.x
        val rightTorsoTilt = last.rightShoulder.x - last.rightHip.x
        val leftTorsoTilt = last.leftShoulder.x - last.leftHip.x
        val rightKneeAngleFirst = first.kneeAngleRight()
        val rightKneeAngleLast = last.kneeAngleRight()
        val leftKneeAngleFirst = first.kneeAngleLeft()
        val leftKneeAngleLast = last.kneeAngleLeft()
        val leftAnkleYs = frames.map { it.leftAnkle.y }
        val rightAnkleYs = frames.map { it.rightAnkle.y }
        val leftAnkleRange = leftAnkleYs.maxOrNull()!! - leftAnkleYs.minOrNull()!!
        val rightAnkleRange = rightAnkleYs.maxOrNull()!! - rightAnkleYs.minOrNull()!!
        val hasToePickLeft = leftAnkleRange > toePickRangeThreshold
        val hasToePickRight = rightAnkleRange > toePickRangeThreshold
        val hasToePick = hasToePickLeft || hasToePickRight
        val hipOverLeftFoot = abs(first.hipCenter().x - first.leftAnkle.x) < hipOverFootThreshold
        val hipOverRightFoot = abs(first.hipCenter().x - first.rightAnkle.x) < hipOverFootThreshold
        val bothKneesBentFirst =
            rightKneeAngleFirst < kneeBentAngle && leftKneeAngleFirst < kneeBentAngle && abs(
                rightKneeAngleFirst - leftKneeAngleFirst
            ) < 10.0
        val rightKneeExtends = rightKneeAngleLast > max(rightKneeAngleFirst, kneeExtendedAngle)
        val leftKneeExtends = leftKneeAngleLast > max(leftKneeAngleFirst, kneeExtendedAngle)
        val rightAnkleForwardThenUp =
            first.rightAnkle.y > last.rightAnkle.y && dyHip < 0f
        val leftAnkleForwardThenUp =
            first.leftAnkle.y > last.leftAnkle.y && dyHip < 0f

        if (!hasToePick) {
            if (isForward && leftEdgeOffset > edgeOffsetMedium && leftKneeAngleFirst < kneeBentAngleTakeoff && leftKneeExtends &&
                shoulderCenterFirst.y < hipCenterFirst.y && (leftAnkleForwardThenUp || rightAnkleForwardThenUp)
            ) {
                return JumpType.AXEL
            }
            if (isBackward && leftEdgeOffset < -edgeOffsetSmall && leftKneeAngleFirst < kneeBentAngleTakeoff && leftKneeExtends &&
                hipOverLeftFoot
            ) {
                return JumpType.SALCHOW
            }
            if (isBackward && rightEdgeOffset > edgeOffsetSmall && bothKneesBentFirst && rightKneeExtends &&
                abs(rightTorsoTilt) < torsoTiltSmall && abs(leftTorsoTilt) < torsoTiltSmall
            ) {
                return JumpType.LOOP
            }
            return JumpType.UNKNOWN
        }

        val toeIsLeft = hasToePickLeft && leftAnkleYs.last() < rightAnkleYs.last()
        val toeIsRight = hasToePickRight && rightAnkleYs.last() < leftAnkleYs.last()

        if (isBackward && rightEdgeOffset > edgeOffsetMedium && (toeIsLeft || hasToePickLeft)) {
            val ankleXDiff = abs(last.leftAnkle.x - last.rightAnkle.x)
            val torsoTiltLargeValue =
                abs(rightTorsoTilt) > torsoTiltLarge || abs(leftTorsoTilt) > torsoTiltLarge
            if (rightEdgeOffset > edgeOffsetLarge && ankleXDiff > ankleSeparationLarge && torsoTiltLargeValue) {
                return JumpType.LUTZ
            }
            return JumpType.TOE_LOOP
        }

        if (isBackward && leftEdgeOffset < -edgeOffsetMedium && (toeIsRight || hasToePickRight)) {
            return JumpType.FLIP
        }

        return JumpType.UNKNOWN
    }

    data class Joint(val x: Float, val y: Float)

    data class FrameFeature(
        val timestampMs: Long,
        val leftHip: Joint,
        val rightHip: Joint,
        val leftKnee: Joint,
        val rightKnee: Joint,
        val leftAnkle: Joint,
        val rightAnkle: Joint,
        val leftShoulder: Joint,
        val rightShoulder: Joint
    ) {
        fun hipCenter(): Joint {
            return Joint(
                (leftHip.x + rightHip.x) / 2f,
                (leftHip.y + rightHip.y) / 2f
            )
        }

        fun shoulderCenter(): Joint {
            return Joint(
                (leftShoulder.x + rightShoulder.x) / 2f,
                (leftShoulder.y + rightShoulder.y) / 2f
            )
        }

        fun kneeAngleLeft(): Double {
            return kneeAngle(leftHip, leftKnee, leftAnkle)
        }

        fun kneeAngleRight(): Double {
            return kneeAngle(rightHip, rightKnee, rightAnkle)
        }

        private fun kneeAngle(hip: Joint, knee: Joint, ankle: Joint): Double {
            val ax = hip.x - knee.x
            val ay = hip.y - knee.y
            val bx = ankle.x - knee.x
            val by = ankle.y - knee.y
            val dot = ax * bx + ay * by
            val magA = kotlin.math.sqrt((ax * ax + ay * ay).toDouble())
            val magB = kotlin.math.sqrt((bx * bx + by * by).toDouble())
            if (magA == 0.0 || magB == 0.0) return 180.0
            val cos = (dot / (magA * magB)).coerceIn(-1.0, 1.0)
            return Math.toDegrees(kotlin.math.acos(cos))
        }
    }

    enum class JumpType {
        TOE_LOOP,
        FLIP,
        LUTZ,
        SALCHOW,
        LOOP,
        AXEL,
        UNKNOWN
    }

    data class JumpEvent(
        val timestampMs: Long,
        val heightCm: Double,
        val airtimeMs: Long,
        val rotationDeg: Double,
        val rotationTurns: Double,
        val label: String,
        val type: JumpType
    )
}
