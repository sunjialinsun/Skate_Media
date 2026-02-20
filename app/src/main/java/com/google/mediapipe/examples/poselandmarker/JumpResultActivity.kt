package com.google.mediapipe.examples.poselandmarker

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.mediapipe.examples.poselandmarker.databinding.ActivityJumpResultBinding

class JumpResultActivity : AppCompatActivity() {

    private lateinit var binding: ActivityJumpResultBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityJumpResultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val sessionId = intent.getLongExtra("session_id", -1L)
        if (sessionId <= 0) {
            finish()
            return
        }

        val session = JumpSessionStore.getById(this, sessionId)
        if (session == null) {
            finish()
            return
        }

        showSummary(session)
        showChart(session)
        showList(session)
    }

    private fun showSummary(session: JumpSession) {
        val count = session.results.size
        val maxHeight = session.results.maxOfOrNull { it.heightCm } ?: 0.0
        val maxTurns = session.results.maxOfOrNull { it.rotationTurns } ?: 0.0
        val maxAirtime = session.results.maxOfOrNull { it.airtimeMs } ?: 0L

        binding.tvSummary.text = "共检测到 $count 次跳跃"
        binding.tvSummaryDetail.text =
            "最高高度：%.1f cm\n最长滞空：%d ms\n最大转体：%.2f 转".format(
                maxHeight,
                maxAirtime,
                maxTurns
            )
    }

    private fun showChart(session: JumpSession) {
        val container = binding.chartContainer
        container.removeAllViews()
        if (session.results.isEmpty()) return

        val maxHeight = session.results.maxOf { it.heightCm }.coerceAtLeast(1.0)
        val barColor = ContextCompat.getColor(this, R.color.ios_blue)

        session.results.forEach { result ->
            val barWrapper = LinearLayout(this)
            barWrapper.orientation = LinearLayout.VERTICAL
            barWrapper.layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f
            )

            val bar = View(this)
            val heightRatio = (result.heightCm / maxHeight).toFloat()
            val barHeight = (120 * heightRatio).toInt()
            val barLayoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                barHeight
            )
            barLayoutParams.bottomMargin = 4
            bar.layoutParams = barLayoutParams
            bar.setBackgroundColor(barColor)

            val label = TextView(this)
            label.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            label.text = String.format("%.0f", result.heightCm)
            label.textSize = 10f
            label.setTextColor(ContextCompat.getColor(this, R.color.ios_text_secondary))
            label.textAlignment = View.TEXT_ALIGNMENT_CENTER

            barWrapper.addView(bar)
            barWrapper.addView(label)
            container.addView(barWrapper)
        }
    }

    private fun showList(session: JumpSession) {
        val container = binding.listContainer
        container.removeAllViews()

        session.results.forEachIndexed { index, result ->
            val textView = TextView(this)
            textView.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            textView.text =
                "第 ${index + 1} 次：${result.label}\n高度：%.1f cm  滞空：%d ms  转体：%.2f 转".format(
                    result.heightCm,
                    result.airtimeMs,
                    result.rotationTurns
                )
            textView.textSize = 14f
            textView.setTextColor(ContextCompat.getColor(this, R.color.ios_text_primary))
            textView.setPadding(0, 8, 0, 8)
            container.addView(textView)
        }
    }
}

