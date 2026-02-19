package com.google.mediapipe.examples.poselandmarker

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mediapipe.examples.poselandmarker.databinding.ActivitySkateBinding
import com.google.mediapipe.tasks.vision.core.RunningMode
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

    private val assumedBodyHeightCm = 160.0
    private val frameWindow = ArrayDeque<FrameFeature>()
    private val windowSize = 5

    private val cameraPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                initPoseLandmarker()
                startCamera()
            } else {
                binding.tvPoseInfo.text = "未授予摄像头权限"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySkateBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        binding.btnStart.setOnClickListener {
            if (!running) {
                requestCameraAndStart()
            } else {
                stopAnalysis()
            }
        }
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
                binding.btnStart.text = "停止捕捉"
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    private fun bindUseCases() {
        val provider = cameraProvider ?: return
        provider.unbindAll()

        val preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            .build()
            .also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }

        imageAnalyzer = ImageAnalysis.Builder()
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

        provider.bindToLifecycle(
            this,
            cameraSelector,
            preview,
            imageAnalyzer
        )
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
        binding.btnStart.text = "开始 AI 实时动作捕捉"
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
        runOnUiThread {
            binding.tvPoseInfo.append("\n错误: $error")
        }
    }

    override fun onResults(resultBundle: PoseLandmarkerHelper.ResultBundle) {
        if (resultBundle.results.isEmpty()) return
        val result = resultBundle.results[0]
        val poseList = result.landmarks()
        if (poseList.isEmpty()) return
        val landmarks = poseList[0]

        val timestampMs = result.timestampMs()
        val leftHipIndex = 23
        val rightHipIndex = 24
        if (landmarks.size <= rightHipIndex) return
        val leftHip = landmarks[leftHipIndex]
        val rightHip = landmarks[rightHipIndex]
        val centerX = (leftHip.x() + rightHip.x()) / 2f
        val centerY = (leftHip.y() + rightHip.y()) / 2f

        val leftKnee = landmarks[25]
        val rightKnee = landmarks[26]
        val leftAnkleLandmark = landmarks[27]
        val rightAnkleLandmark = landmarks[28]
        val leftShoulder = landmarks[11]
        val rightShoulder = landmarks[12]

        val frameFeature = FrameFeature(
            timestampMs = timestampMs,
            leftHip = Joint(leftHip.x(), leftHip.y()),
            rightHip = Joint(rightHip.x(), rightHip.y()),
            leftKnee = Joint(leftKnee.x(), leftKnee.y()),
            rightKnee = Joint(rightKnee.x(), rightKnee.y()),
            leftAnkle = Joint(leftAnkleLandmark.x(), leftAnkleLandmark.y()),
            rightAnkle = Joint(rightAnkleLandmark.x(), rightAnkleLandmark.y()),
            leftShoulder = Joint(leftShoulder.x(), leftShoulder.y()),
            rightShoulder = Joint(rightShoulder.x(), rightShoulder.y())
        )
        if (frameWindow.size >= windowSize) {
            frameWindow.removeFirst()
        }
        frameWindow.addLast(frameFeature)

        val rawAngleDeg = Math.toDegrees(
            atan2(
                (rightHip.y() - leftHip.y()).toDouble(),
                (rightHip.x() - leftHip.x()).toDouble()
            )
        )

        val previousAngle = lastAngleDeg
        val previousTimestamp = lastTimestampMs
        var deltaAngleDeg = 0.0
        var angularVelocityTurnsPerSec = 0.0
        if (previousAngle != null && previousTimestamp != null) {
            var delta = rawAngleDeg - previousAngle
            if (delta > 180.0) delta -= 360.0
            if (delta < -180.0) delta += 360.0
            angleUnwrappedDeg += delta
            deltaAngleDeg = delta
            val dtMs = timestampMs - previousTimestamp
            if (dtMs > 0) {
                val dtSec = dtMs.toDouble() / 1000.0
                angularVelocityTurnsPerSec = abs(deltaAngleDeg) / 360.0 / dtSec
            }
        }
        lastAngleDeg = rawAngleDeg
        lastTimestampMs = timestampMs

        val leftAnkleIndex = 27
        val rightAnkleIndex = 28
        var ankleY = centerY
        if (landmarks.size > rightAnkleIndex) {
            val leftAnkle = landmarks[leftAnkleIndex]
            val rightAnkle = landmarks[rightAnkleIndex]
            ankleY = (leftAnkle.y() + rightAnkle.y()) / 2f
        }

        val currentGround = groundAnkleY
        if (currentGround == null) {
            groundAnkleY = ankleY
        } else if (!inAir) {
            groundAnkleY = currentGround * 0.9f + ankleY * 0.1f
        }

        val ground = groundAnkleY ?: ankleY
        val heightRel = ground - ankleY
        val isCurrentlyAir = heightRel > 0.05f

        if (isCurrentlyAir && !inAir) {
            inAir = true
            jumpStartTimeMs = timestampMs
            jumpStartAngleUnwrappedDeg = angleUnwrappedDeg
            jumpMinAnkleY = ankleY
            jumpStartHipX = centerX
            jumpStartHipY = centerY
        } else if (isCurrentlyAir && inAir) {
            if (ankleY < jumpMinAnkleY) {
                jumpMinAnkleY = ankleY
            }
        } else if (!isCurrentlyAir && inAir) {
            val airtimeMs = timestampMs - jumpStartTimeMs
            val noseIndex = 0
            var bodySpan = 0.0
            if (landmarks.size > leftAnkleIndex && landmarks.size > noseIndex) {
                val nose = landmarks[noseIndex]
                val landingAnkleY = ankleY
                bodySpan = abs((nose.y() - landingAnkleY).toDouble())
            }
            val scaleCmPerNorm = if (bodySpan > 0.0) assumedBodyHeightCm / bodySpan else 0.0
            val jumpHeightNorm = ground - jumpMinAnkleY
            val jumpHeightCm = jumpHeightNorm.toDouble() * scaleCmPerNorm
            val rotationDeltaDeg = angleUnwrappedDeg - jumpStartAngleUnwrappedDeg
            val rotationDeg = abs(rotationDeltaDeg)
            val rotationTurns = rotationDeg / 360.0
            val absTurns = rotationTurns
            val dxJump = centerX - jumpStartHipX
            val dyJump = centerY - jumpStartHipY
            val jumpMoveDist = kotlin.math.sqrt((dxJump * dxJump + dyJump * dyJump).toDouble())
            val jumpMoveMax = 0.3
            val minHeightCm = 8.0
            val minAirtimeMs = 200L
            val minTurns = 0.5
            if (jumpHeightCm < minHeightCm && airtimeMs < minAirtimeMs && absTurns < minTurns) {
                inAir = false
                return
            }
            val baseLabel =
                if (airtimeMs < 150 || jumpHeightCm < 5.0) {
                    "小跳"
                } else if (absTurns < 0.75) {
                    "未完成单跳"
                } else if (absTurns < 1.5) {
                    "单跳"
                } else if (absTurns < 2.5) {
                    "双跳"
                } else if (absTurns < 3.5) {
                    "三跳"
                } else {
                    "多周跳"
                }
            if (jumpMoveDist > jumpMoveMax) {
                inAir = false
                return
            }
            val jumpType = classifyJumpType()
            val typeLabel = when (jumpType) {
                JumpType.TOE_LOOP -> "Toe Loop 后外点冰跳"
                JumpType.FLIP -> "Flip 后内点冰跳"
                JumpType.LUTZ -> "Lutz 勾手跳"
                JumpType.SALCHOW -> "Salchow 后内结环跳"
                JumpType.LOOP -> "Loop 后外结环跳"
                JumpType.AXEL -> "Axel 阿克塞尔前外跳"
                JumpType.UNKNOWN -> "未知跳跃"
            }
            val label = "$typeLabel $baseLabel"
            jumpEvents.add(
                JumpEvent(
                    timestampMs = timestampMs,
                    heightCm = jumpHeightCm,
                    airtimeMs = airtimeMs,
                    rotationDeg = rotationDeg,
                    rotationTurns = rotationTurns,
                    label = label,
                    type = jumpType
                )
            )
            inAir = false
        }

        val instantFps =
            if (resultBundle.inferenceTime > 0) 1000.0 / resultBundle.inferenceTime.toDouble()
            else 0.0
        fpsSmoothed =
            if (fpsSmoothed == 0.0) instantFps else fpsSmoothed * 0.8 + instantFps * 0.2

        val builder = StringBuilder()
        builder.append("帧推理时间: ${resultBundle.inferenceTime} ms\n")
        builder.append("关节点数量: ${landmarks.size}\n")
        builder.append("总旋转圈数: ${"%.2f".format(abs(angleUnwrappedDeg / 360.0))}\n")
        builder.append("当前旋转速度: ${"%.2f".format(angularVelocityTurnsPerSec)} 转/秒\n")

        if (landmarks.size > 28) {
            val nose = landmarks[0]
            val leftHip = landmarks[23]
            val rightHip = landmarks[24]
            val leftAnkleLandmark = landmarks[27]
            val rightAnkleLandmark = landmarks[28]
            val torsoX = (leftHip.x() + rightHip.x()) / 2f
            val torsoY = (leftHip.y() + rightHip.y()) / 2f
            val torsoZ = (leftHip.z() + rightHip.z()) / 2f
            val ankleX = (leftAnkleLandmark.x() + rightAnkleLandmark.x()) / 2f
            val ankleY = (leftAnkleLandmark.y() + rightAnkleLandmark.y()) / 2f
            val ankleZ = (leftAnkleLandmark.z() + rightAnkleLandmark.z()) / 2f
            builder.append(
                "H x=${"%.3f".format(nose.x())} y=${"%.3f".format(nose.y())} z=${"%.3f".format(nose.z())}  " +
                        "T x=${"%.3f".format(torsoX)} y=${"%.3f".format(torsoY)} z=${"%.3f".format(torsoZ)}  " +
                        "A x=${"%.3f".format(ankleX)} y=${"%.3f".format(ankleY)} z=${"%.3f".format(ankleZ)}\n"
            )
        }

        if (jumpEvents.isNotEmpty()) {
            builder.append("\nRecent jumps:\n")
            jumpEvents.asReversed().take(3).forEach { jump ->
                val rev = kotlin.math.round(jump.rotationTurns).toInt().coerceAtLeast(1)
                val typeCode = when (jump.type) {
                    JumpType.TOE_LOOP -> "${rev}T"
                    JumpType.FLIP -> "${rev}F"
                    JumpType.LUTZ -> "${rev}Lz"
                    JumpType.SALCHOW -> "${rev}S"
                    JumpType.LOOP -> "${rev}Lo"
                    JumpType.AXEL -> "${rev}A"
                    JumpType.UNKNOWN -> "Jump?"
                }
                builder.append(
                    "t=${jump.timestampMs}ms $typeCode " +
                            "height=${"%.1f".format(jump.heightCm)}cm " +
                            "airtime=${jump.airtimeMs}ms " +
                            "rotation=${"%.1f".format(jump.rotationDeg)}deg " +
                            "(${String.format("%.2f", jump.rotationTurns)} turns)\n"
                )
            }
        }

        runOnUiThread {
            binding.overlayView.setResults(
                result,
                resultBundle.inputImageHeight,
                resultBundle.inputImageWidth,
                RunningMode.LIVE_STREAM
            )
            binding.tvPoseInfo.text = builder.toString()
            binding.scrollInfo.post {
                binding.scrollInfo.fullScroll(View.FOCUS_DOWN)
            }
            val text = "FPS ${"%.1f".format(fpsSmoothed)}"
            binding.tvFps.text = text
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
        val hasToePickLeft = leftAnkleRange > 0.06f
        val hasToePickRight = rightAnkleRange > 0.06f
        val hasToePick = hasToePickLeft || hasToePickRight
        val hipOverLeftFoot = abs(first.hipCenter().x - first.leftAnkle.x) < 0.03f
        val hipOverRightFoot = abs(first.hipCenter().x - first.rightAnkle.x) < 0.03f
        val bothKneesBentFirst =
            rightKneeAngleFirst < 140.0 && leftKneeAngleFirst < 140.0 && abs(rightKneeAngleFirst - leftKneeAngleFirst) < 10.0
        val rightKneeExtends = rightKneeAngleLast > max(rightKneeAngleFirst, 150.0)
        val leftKneeExtends = leftKneeAngleLast > max(leftKneeAngleFirst, 150.0)
        val rightAnkleForwardThenUp =
            first.rightAnkle.y > last.rightAnkle.y && dyHip < 0f
        val leftAnkleForwardThenUp =
            first.leftAnkle.y > last.leftAnkle.y && dyHip < 0f

        if (!hasToePick) {
            if (isForward && leftEdgeOffset > 0.05f && leftKneeAngleFirst < 120.0 && leftKneeExtends &&
                shoulderCenterFirst.y < hipCenterFirst.y && (leftAnkleForwardThenUp || rightAnkleForwardThenUp)
            ) {
                return JumpType.AXEL
            }
            if (isBackward && leftEdgeOffset < -0.02f && leftKneeAngleFirst < 120.0 && leftKneeExtends &&
                hipOverLeftFoot
            ) {
                return JumpType.SALCHOW
            }
            if (isBackward && rightEdgeOffset > 0.02f && bothKneesBentFirst && rightKneeExtends &&
                abs(rightTorsoTilt) < 0.05f && abs(leftTorsoTilt) < 0.05f
            ) {
                return JumpType.LOOP
            }
            return JumpType.UNKNOWN
        }

        val toeIsLeft = hasToePickLeft && leftAnkleYs.last() < rightAnkleYs.last()
        val toeIsRight = hasToePickRight && rightAnkleYs.last() < leftAnkleYs.last()

        if (isBackward && rightEdgeOffset > 0.05f && (toeIsLeft || hasToePickLeft)) {
            val ankleXDiff = abs(last.leftAnkle.x - last.rightAnkle.x)
            val torsoTiltLarge = abs(rightTorsoTilt) > 0.1f || abs(leftTorsoTilt) > 0.1f
            if (rightEdgeOffset > 0.1f && ankleXDiff > 0.15f && torsoTiltLarge) {
                return JumpType.LUTZ
            }
            return JumpType.TOE_LOOP
        }

        if (isBackward && leftEdgeOffset < -0.05f && (toeIsRight || hasToePickRight)) {
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
