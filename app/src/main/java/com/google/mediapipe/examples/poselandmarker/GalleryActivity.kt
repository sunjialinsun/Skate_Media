package com.google.mediapipe.examples.poselandmarker

import android.content.ContentUris
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.google.mediapipe.examples.poselandmarker.databinding.ActivityGalleryBinding

class GalleryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGalleryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val modeName = intent.getStringExtra("mode") ?: "JUMP"
        val mode = if (modeName == "SPIN") GalleryMode.SPIN else GalleryMode.JUMP

        val items = loadVideos(mode)
        if (items.isEmpty()) {
            binding.listView.visibility = View.GONE
            binding.tvEmpty.visibility = View.VISIBLE
            return
        }

        binding.listView.visibility = View.VISIBLE
        binding.tvEmpty.visibility = View.GONE

        val titles = items.map { it.second }

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            titles
        )
        binding.listView.adapter = adapter

        binding.listView.onItemClickListener =
            AdapterView.OnItemClickListener { _, _, position, _ ->
                val uri = items[position].first
                val name = items[position].second
                val intentAdvanced = Intent(this, AdvancedDataActivity::class.java)
                intentAdvanced.putExtra("video_uri", uri.toString())
                intentAdvanced.putExtra("video_name", name)
                startActivity(intentAdvanced)
            }
    }

    private fun loadVideos(mode: GalleryMode): List<Pair<android.net.Uri, String>> {
        val result = mutableListOf<Pair<android.net.Uri, String>>()
        val prefix = if (mode == GalleryMode.JUMP) "jump_" else "spin_"

        val collection =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME
        )

        val selection = "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("$prefix%")

        contentResolver.query(
            collection,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn)
                val uri = ContentUris.withAppendedId(collection, id)
                result.add(uri to name)
            }
        }

        return result
    }

    private enum class GalleryMode {
        JUMP,
        SPIN
    }
}
