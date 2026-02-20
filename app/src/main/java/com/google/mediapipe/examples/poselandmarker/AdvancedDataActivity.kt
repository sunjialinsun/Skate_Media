package com.google.mediapipe.examples.poselandmarker

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.mediapipe.examples.poselandmarker.databinding.ActivityAdvancedDataBinding

class AdvancedDataActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdvancedDataBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdvancedDataBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val videoName = intent.getStringExtra("video_name") ?: ""
        val videoUriString = intent.getStringExtra("video_uri") ?: ""

        if (videoName.isNotEmpty()) {
            binding.tvVideoName.text = videoName
        }

        val sessions =
            if (videoUriString.isNotEmpty()) JumpSessionStore.getByVideoUri(this, videoUriString)
            else emptyList()

        if (sessions.isNotEmpty()) {
            val latest = sessions.maxBy { it.createdAt }
            val count = sessions.size
            binding.tvHint.text =
                "已保存 $count 次跳跃分析，最近一次最高高度 %.1f cm，最大转体 %.2f 转".format(
                    latest.results.maxOfOrNull { it.heightCm } ?: 0.0,
                    latest.results.maxOfOrNull { it.rotationTurns } ?: 0.0
                )
            binding.btnViewLastAnalysis.visibility = View.VISIBLE
            binding.btnViewLastAnalysis.setOnClickListener {
                val intentResult = Intent(this, JumpResultActivity::class.java)
                intentResult.putExtra("session_id", latest.id)
                startActivity(intentResult)
            }
        } else {
            binding.tvHint.text = "暂无已保存的跳跃分析。"
            binding.btnViewLastAnalysis.visibility = View.GONE
        }

        binding.btnViewSkeleton.setOnClickListener {
            val intentSkeleton = Intent(this, Skeleton3DActivity::class.java)
            intentSkeleton.putExtra("video_uri", videoUriString)
            startActivity(intentSkeleton)
        }
    }
}
