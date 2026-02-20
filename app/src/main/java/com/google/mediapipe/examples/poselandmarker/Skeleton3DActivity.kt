package com.google.mediapipe.examples.poselandmarker

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.mediapipe.examples.poselandmarker.databinding.ActivitySkeleton3dBinding

class Skeleton3DActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySkeleton3dBinding

    private var playing = false
    private var speedIndex = 0
    private val speeds = floatArrayOf(0.5f, 1.0f, 1.5f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySkeleton3dBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val frames = generateDemoFrames()
        binding.skeletonView.setFrames(frames)
        updateFrameLabel()

        selectTab(binding.tabStandard, Skeleton3DView.Mode.STANDARD)

        binding.tabStandard.setOnClickListener {
            selectTab(binding.tabStandard, Skeleton3DView.Mode.STANDARD)
        }
        binding.tabLeftRight.setOnClickListener {
            selectTab(binding.tabLeftRight, Skeleton3DView.Mode.LEFT_RIGHT)
        }
        binding.tabHipAngle.setOnClickListener {
            selectTab(binding.tabHipAngle, Skeleton3DView.Mode.HIP_ANGLE)
        }

        binding.btnClose.setOnClickListener {
            finish()
        }

        binding.btnPlayPause.setOnClickListener {
            if (playing) {
                stopPlayback()
            } else {
                startPlayback()
            }
        }

        binding.btnSpeed.setOnClickListener {
            speedIndex = (speedIndex + 1) % speeds.size
            binding.btnSpeed.text = speeds[speedIndex].toString() + "x"
        }
    }

    private fun selectTab(tab: TextView, mode: Skeleton3DView.Mode) {
        binding.tabStandard.setBackgroundColor(0x00000000)
        binding.tabLeftRight.setBackgroundColor(0x00000000)
        binding.tabHipAngle.setBackgroundColor(0x00000000)

        binding.tabStandard.setTextColor(resources.getColor(R.color.ios_text_secondary))
        binding.tabLeftRight.setTextColor(resources.getColor(R.color.ios_text_secondary))
        binding.tabHipAngle.setTextColor(resources.getColor(R.color.ios_text_secondary))

        tab.setBackgroundColor(resources.getColor(R.color.ios_card))
        tab.setTextColor(resources.getColor(R.color.ios_text_primary))

        binding.skeletonView.setMode(mode)
    }

    private fun startPlayback() {
        playing = true
        binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
        binding.skeletonView.removeCallbacks(playRunnable)
        binding.skeletonView.post(playRunnable)
    }

    private fun stopPlayback() {
        playing = false
        binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
        binding.skeletonView.removeCallbacks(playRunnable)
    }

    private val playRunnable = object : Runnable {
        override fun run() {
            if (!playing) return
            val count = binding.skeletonView.getFrameCount()
            if (count <= 0) return
            val next = (binding.skeletonView.getCurrentFrameIndex() + 1) % count
            binding.skeletonView.setFrameIndex(next)
            updateFrameLabel()
            val delay = (1000f / 30f / speeds[speedIndex]).toLong()
            binding.skeletonView.postDelayed(this, delay)
        }
    }

    private fun updateFrameLabel() {
        val index = binding.skeletonView.getCurrentFrameIndex()
        val total = binding.skeletonView.getFrameCount()
        binding.tvFrame.text = "Frame: $index / $total"
    }

    private fun generateDemoFrames(): List<Skeleton3DView.Frame> {
        val frames = mutableListOf<Skeleton3DView.Frame>()
        val pointCount = 33
        val frameTotal = 180
        for (i in 0 until frameTotal) {
            val t = i / frameTotal.toFloat()
            val points = mutableListOf<Skeleton3DView.Point3>()
            for (j in 0 until pointCount) {
                val x = (j - pointCount / 2) * 0.02f
                val y = (j % 10) * 0.02f + 0.3f + 0.1f * kotlin.math.sin(t * 6.28f)
                val z = 0.1f * kotlin.math.cos(t * 6.28f)
                points.add(Skeleton3DView.Point3(x, y, z))
            }
            frames.add(Skeleton3DView.Frame(points))
        }
        return frames
    }
}

