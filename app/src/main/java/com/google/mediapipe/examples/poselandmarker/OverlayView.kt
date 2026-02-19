/*
 * Copyright 2023 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.mediapipe.examples.poselandmarker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import kotlin.math.max
import kotlin.math.min

class OverlayView(context: Context?, attrs: AttributeSet?) :
    View(context, attrs) {

    private var results: PoseLandmarkerResult? = null
    private var pointPaint = Paint()
    private var headPaint = Paint()
    private var torsoPaint = Paint()
    private var leftArmPaint = Paint()
    private var rightArmPaint = Paint()
    private var leftLegPaint = Paint()
    private var rightLegPaint = Paint()

    private var scaleFactor: Float = 1f
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1

    init {
        initPaints()
    }

    fun clear() {
        results = null
        pointPaint.reset()
        headPaint.reset()
        torsoPaint.reset()
        leftArmPaint.reset()
        rightArmPaint.reset()
        leftLegPaint.reset()
        rightLegPaint.reset()
        invalidate()
        initPaints()
    }

    private fun initPaints() {
        pointPaint.color = Color.YELLOW
        pointPaint.strokeWidth = LANDMARK_STROKE_WIDTH
        pointPaint.style = Paint.Style.FILL

        headPaint.color = ContextCompat.getColor(context!!, R.color.skeleton_head)
        headPaint.strokeWidth = LANDMARK_STROKE_WIDTH
        headPaint.style = Paint.Style.STROKE

        torsoPaint.color = ContextCompat.getColor(context!!, R.color.skeleton_torso)
        torsoPaint.strokeWidth = LANDMARK_STROKE_WIDTH
        torsoPaint.style = Paint.Style.STROKE

        leftArmPaint.color = ContextCompat.getColor(context!!, R.color.skeleton_left_arm)
        leftArmPaint.strokeWidth = LANDMARK_STROKE_WIDTH
        leftArmPaint.style = Paint.Style.STROKE

        rightArmPaint.color = ContextCompat.getColor(context!!, R.color.skeleton_right_arm)
        rightArmPaint.strokeWidth = LANDMARK_STROKE_WIDTH
        rightArmPaint.style = Paint.Style.STROKE

        leftLegPaint.color = ContextCompat.getColor(context!!, R.color.skeleton_left_leg)
        leftLegPaint.strokeWidth = LANDMARK_STROKE_WIDTH
        leftLegPaint.style = Paint.Style.STROKE

        rightLegPaint.color = ContextCompat.getColor(context!!, R.color.skeleton_right_leg)
        rightLegPaint.strokeWidth = LANDMARK_STROKE_WIDTH
        rightLegPaint.style = Paint.Style.STROKE
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        results?.let { poseLandmarkerResult ->
            for(landmark in poseLandmarkerResult.landmarks()) {
                for(normalizedLandmark in landmark) {
                    canvas.drawPoint(
                        normalizedLandmark.x() * imageWidth * scaleFactor,
                        normalizedLandmark.y() * imageHeight * scaleFactor,
                        pointPaint
                    )
                }

                PoseLandmarker.POSE_LANDMARKS.forEach { connection ->
                    if (connection == null) return@forEach
                    val start = connection.start()
                    val end = connection.end()
                    val paint = when {
                        isHeadConnection(start, end) -> headPaint
                        isTorsoConnection(start, end) -> torsoPaint
                        isLeftArmConnection(start, end) -> leftArmPaint
                        isRightArmConnection(start, end) -> rightArmPaint
                        isLeftLegConnection(start, end) -> leftLegPaint
                        isRightLegConnection(start, end) -> rightLegPaint
                        else -> torsoPaint
                    }
                    canvas.drawLine(
                        poseLandmarkerResult.landmarks().get(0).get(start).x() * imageWidth * scaleFactor,
                        poseLandmarkerResult.landmarks().get(0).get(start).y() * imageHeight * scaleFactor,
                        poseLandmarkerResult.landmarks().get(0).get(end).x() * imageWidth * scaleFactor,
                        poseLandmarkerResult.landmarks().get(0).get(end).y() * imageHeight * scaleFactor,
                        paint
                    )
                }
            }
        }
    }

    fun setResults(
        poseLandmarkerResults: PoseLandmarkerResult,
        imageHeight: Int,
        imageWidth: Int,
        runningMode: RunningMode = RunningMode.IMAGE
    ) {
        results = poseLandmarkerResults

        this.imageHeight = imageHeight
        this.imageWidth = imageWidth

        scaleFactor = when (runningMode) {
            RunningMode.IMAGE,
            RunningMode.VIDEO -> {
                min(width * 1f / imageWidth, height * 1f / imageHeight)
            }
            RunningMode.LIVE_STREAM -> {
                // PreviewView is in FILL_START mode. So we need to scale up the
                // landmarks to match with the size that the captured images will be
                // displayed.
                max(width * 1f / imageWidth, height * 1f / imageHeight)
            }
        }
        invalidate()
    }

    companion object {
        private const val LANDMARK_STROKE_WIDTH = 8F
    }

    private fun isHeadConnection(start: Int, end: Int): Boolean {
        val head = setOf(0, 1, 2, 3, 4, 5, 6, 7)
        return head.contains(start) && head.contains(end)
    }

    private fun isTorsoConnection(start: Int, end: Int): Boolean {
        val torso = setOf(11, 12, 23, 24)
        return torso.contains(start) && torso.contains(end)
    }

    private fun isLeftArmConnection(start: Int, end: Int): Boolean {
        val leftArm = setOf(11, 13, 15)
        return leftArm.contains(start) && leftArm.contains(end)
    }

    private fun isRightArmConnection(start: Int, end: Int): Boolean {
        val rightArm = setOf(12, 14, 16)
        return rightArm.contains(start) && rightArm.contains(end)
    }

    private fun isLeftLegConnection(start: Int, end: Int): Boolean {
        val leftLeg = setOf(23, 25, 27)
        return leftLeg.contains(start) && leftLeg.contains(end)
    }

    private fun isRightLegConnection(start: Int, end: Int): Boolean {
        val rightLeg = setOf(24, 26, 28)
        return rightLeg.contains(start) && rightLeg.contains(end)
    }
}
