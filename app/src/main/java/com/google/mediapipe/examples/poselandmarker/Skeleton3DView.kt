package com.google.mediapipe.examples.poselandmarker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class Skeleton3DView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    data class Point3(val x: Float, val y: Float, val z: Float)

    data class Frame(val points: List<Point3>)

    private val pointPaint = Paint()
    private val bonePaint = Paint()

    private var frames: List<Frame> = emptyList()
    private var currentFrameIndex: Int = 0
    private var mode: Mode = Mode.STANDARD

    enum class Mode {
        STANDARD,
        LEFT_RIGHT,
        HIP_ANGLE
    }

    init {
        pointPaint.color = Color.GREEN
        pointPaint.style = Paint.Style.FILL
        pointPaint.strokeWidth = 10f

        bonePaint.color = Color.WHITE
        bonePaint.style = Paint.Style.STROKE
        bonePaint.strokeWidth = 4f
    }

    fun setFrames(frames: List<Frame>) {
        this.frames = frames
        currentFrameIndex = 0
        invalidate()
    }

    fun setMode(mode: Mode) {
        this.mode = mode
        invalidate()
    }

    fun setFrameIndex(index: Int) {
        if (frames.isEmpty()) return
        currentFrameIndex = index.coerceIn(0, frames.lastIndex)
        invalidate()
    }

    fun getFrameCount(): Int {
        return frames.size
    }

    fun getCurrentFrameIndex(): Int {
        return currentFrameIndex
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()

        canvas.drawColor(Color.BLACK)

        if (frames.isEmpty()) return

        val frame = frames[currentFrameIndex]

        val cx = w / 2f
        val cy = h * 0.7f
        val scale = min(w, h) * 0.35f

        val angleY = when (mode) {
            Mode.STANDARD -> 0f
            Mode.LEFT_RIGHT -> 0.6f
            Mode.HIP_ANGLE -> 0.3f
        }

        fun project(p: Point3): Pair<Float, Float> {
            val xRot = p.x * cos(angleY) + p.z * sin(angleY)
            val zRot = -p.x * sin(angleY) + p.z * cos(angleY)
            val k = 1f / (1f + 0.8f * zRot)
            val sx = cx + xRot * scale * k
            val sy = cy - p.y * scale * k
            return sx to sy
        }

        val indicesTorso = listOf(11, 12, 23, 24)
        val indicesLegLeft = listOf(23, 25, 27)
        val indicesLegRight = listOf(24, 26, 28)
        val indicesArmLeft = listOf(11, 13, 15)
        val indicesArmRight = listOf(12, 14, 16)
        val indicesHead = listOf(0, 7)

        fun drawPolyline(indices: List<Int>) {
            for (i in 0 until indices.size - 1) {
                val i0 = indices[i]
                val i1 = indices[i + 1]
                if (i0 >= frame.points.size || i1 >= frame.points.size) continue
                val p0 = project(frame.points[i0])
                val p1 = project(frame.points[i1])
                canvas.drawLine(p0.first, p0.second, p1.first, p1.second, bonePaint)
            }
        }

        drawPolyline(indicesTorso)
        drawPolyline(indicesLegLeft)
        drawPolyline(indicesLegRight)
        drawPolyline(indicesArmLeft)
        drawPolyline(indicesArmRight)
        drawPolyline(indicesHead)

        frame.points.forEach { p ->
            val s = project(p)
            canvas.drawCircle(s.first, s.second, 6f, pointPaint)
        }
    }
}
