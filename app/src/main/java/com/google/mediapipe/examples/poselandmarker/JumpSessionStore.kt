package com.google.mediapipe.examples.poselandmarker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class JumpResult(
    val heightCm: Double,
    val airtimeMs: Long,
    val rotationTurns: Double,
    val type: String,
    val label: String
)

data class JumpSession(
    val id: Long,
    val videoUri: String?,
    val mode: String,
    val createdAt: Long,
    val results: List<JumpResult>
)

object JumpSessionStore {

    private const val FILE_NAME = "jump_sessions.json"

    fun addSession(context: Context, session: JumpSession) {
        val sessions = loadAll(context).toMutableList()
        sessions.add(session)
        saveAll(context, sessions)
    }

    fun getAll(context: Context): List<JumpSession> {
        return loadAll(context)
    }

    fun getById(context: Context, id: Long): JumpSession? {
        return loadAll(context).firstOrNull { it.id == id }
    }

    fun getByVideoUri(context: Context, videoUri: String): List<JumpSession> {
        return loadAll(context).filter { it.videoUri == videoUri }
    }

    private fun loadAll(context: Context): List<JumpSession> {
        val file = context.getFileStreamPath(FILE_NAME)
        if (!file.exists()) return emptyList()
        val text = file.readText()
        if (text.isEmpty()) return emptyList()
        val array = JSONArray(text)
        val list = mutableListOf<JumpSession>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val id = obj.getLong("id")
            val videoUri = if (obj.isNull("videoUri")) null else obj.getString("videoUri")
            val mode = obj.getString("mode")
            val createdAt = obj.getLong("createdAt")
            val resultsArray = obj.getJSONArray("results")
            val results = mutableListOf<JumpResult>()
            for (j in 0 until resultsArray.length()) {
                val r = resultsArray.getJSONObject(j)
                results.add(
                    JumpResult(
                        heightCm = r.getDouble("heightCm"),
                        airtimeMs = r.getLong("airtimeMs"),
                        rotationTurns = r.getDouble("rotationTurns"),
                        type = r.getString("type"),
                        label = r.getString("label")
                    )
                )
            }
            list.add(
                JumpSession(
                    id = id,
                    videoUri = videoUri,
                    mode = mode,
                    createdAt = createdAt,
                    results = results
                )
            )
        }
        return list
    }

    private fun saveAll(context: Context, sessions: List<JumpSession>) {
        val array = JSONArray()
        sessions.forEach { session ->
            val obj = JSONObject()
            obj.put("id", session.id)
            obj.put("videoUri", session.videoUri)
            obj.put("mode", session.mode)
            obj.put("createdAt", session.createdAt)
            val resultsArray = JSONArray()
            session.results.forEach { r ->
                val ro = JSONObject()
                ro.put("heightCm", r.heightCm)
                ro.put("airtimeMs", r.airtimeMs)
                ro.put("rotationTurns", r.rotationTurns)
                ro.put("type", r.type)
                ro.put("label", r.label)
                resultsArray.put(ro)
            }
            obj.put("results", resultsArray)
            array.put(obj)
        }
        context.openFileOutput(FILE_NAME, Context.MODE_PRIVATE).use { out ->
            out.write(array.toString().toByteArray())
        }
    }
}

