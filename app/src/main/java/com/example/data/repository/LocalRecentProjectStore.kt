package com.example.data.repository

import android.content.Context
import com.example.data.model.Project
import com.example.data.model.ProjectPayloadSerializer
import com.google.firebase.Timestamp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Date
import java.util.UUID

/**
 * Synchronous, atomic local project store that guarantees zero-data-loss persistence of recent
 * edits whenever a project is closed, switched, or backgrounded — complementing Firestore cloud
 * sync so recent edits are immediately restored even offline or before cloud round-trips finish.
 */
object LocalRecentProjectStore {

    private val lock = Any()

    fun assignStableProjectId(existingId: String): String {
        return existingId.takeIf { it.isNotBlank() }
            ?: "proj_${UUID.randomUUID().toString().replace("-", "").take(16)}"
    }

    fun upsertProject(
        context: Context?,
        userId: String,
        project: Project
    ): Project {
        val nowMs = System.currentTimeMillis()
        val nowTs = Timestamp(Date(nowMs))
        val stableId = assignStableProjectId(project.id)
        val savedProject = project.copy(
            id = stableId,
            userId = userId.ifBlank { project.userId.ifBlank { "local_user" } },
            createdAt = project.createdAt,
            updatedAt = nowTs
        )
        if (context == null) return savedProject

        synchronized(lock) {
            val currentList = readProjectsLocked(context, userId).toMutableList()
            val existingIdx = currentList.indexOfFirst { it.id == stableId }
            if (existingIdx >= 0) {
                val prevCreated = savedProject.createdAt ?: currentList[existingIdx].createdAt
                currentList[existingIdx] = savedProject.copy(createdAt = prevCreated)
            } else {
                currentList.add(0, savedProject)
            }
            val sorted = currentList
                .sortedByDescending { timestampToMillis(it.updatedAt) }
                .take(100)
            writeProjectsLocked(context, userId, sorted)
        }
        return savedProject
    }

    fun deleteProject(context: Context?, userId: String, projectId: String) {
        if (context == null || projectId.isBlank()) return
        synchronized(lock) {
            val remaining = readProjectsLocked(context, userId).filterNot { it.id == projectId }
            writeProjectsLocked(context, userId, remaining)
        }
    }

    fun loadProjects(context: Context?, userId: String): List<Project> {
        if (context == null) return emptyList()
        synchronized(lock) {
            return readProjectsLocked(context, userId)
        }
    }

    fun mergeLocalAndCloudProjects(
        localProjects: List<Project>,
        cloudProjects: List<Project>,
        deletedIds: Set<String> = emptySet()
    ): List<Project> {
        val mergedById = LinkedHashMap<String, Project>()
        for (proj in cloudProjects) {
            if (proj.id.isBlank() || proj.id in deletedIds) continue
            mergedById[proj.id] = proj
        }
        for (local in localProjects) {
            if (local.id.isBlank() || local.id in deletedIds) continue
            val cloud = mergedById[local.id]
            if (cloud == null || timestampToMillis(local.updatedAt) >= timestampToMillis(cloud.updatedAt)) {
                mergedById[local.id] = local
            }
        }
        return mergedById.values.sortedByDescending { timestampToMillis(it.updatedAt) }
    }

    private fun timestampToMillis(ts: Timestamp?): Long {
        if (ts == null) return 0L
        return ts.seconds * 1000L + (ts.nanoseconds / 1_000_000L)
    }

    private fun storeFile(context: Context, userId: String): File {
        val safeUser = userId.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "local_user" }
        val dir = File(context.applicationContext.filesDir, "recent_projects_store").apply { mkdirs() }
        return File(dir, "projects_$safeUser.json")
    }

    private fun readProjectsLocked(context: Context, userId: String): List<Project> {
        val file = storeFile(context, userId)
        if (!file.exists()) return emptyList()
        return runCatching {
            val raw = file.readText()
            if (raw.isBlank()) return emptyList()
            val arr = JSONArray(raw)
            val out = ArrayList<Project>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val layersPayload = obj.optString("layersPayload", "[]")
                val beatsPayload = obj.optString("beatMarkersPayload", "[]")
                val createdMs = obj.optLong("createdAtMs", 0L)
                val updatedMs = obj.optLong("updatedAtMs", createdMs)
                out.add(
                    Project(
                        id = obj.optString("id", ""),
                        userId = obj.optString("userId", userId),
                        name = obj.optString("name", "Untitled Project"),
                        aspectRatioPreset = obj.optString("aspectRatioPreset", "16:9"),
                        width = obj.optInt("width", 1920).coerceIn(64, 7680),
                        height = obj.optInt("height", 1080).coerceIn(64, 7680),
                        fps = obj.optInt("fps", 60).coerceIn(1, 240),
                        durationMs = obj.optLong("durationMs", 10_000L).coerceIn(100L, 3_600_000L),
                        codec = obj.optString("codec", "H.264"),
                        bpm = obj.optInt("bpm", 128).coerceIn(30, 300),
                        backgroundColor = ProjectPayloadSerializer.decodeBackgroundColor(layersPayload),
                        layers = ProjectPayloadSerializer.decodeLayers(layersPayload),
                        beatMarkers = ProjectPayloadSerializer.decodeBeatMarkers(beatsPayload),
                        createdAt = if (createdMs > 0L) Timestamp(Date(createdMs)) else null,
                        updatedAt = if (updatedMs > 0L) Timestamp(Date(updatedMs)) else null
                    )
                )
            }
            out.sortedByDescending { timestampToMillis(it.updatedAt) }
        }.getOrDefault(emptyList())
    }

    private fun writeProjectsLocked(context: Context, userId: String, projects: List<Project>) {
        runCatching {
            val target = storeFile(context, userId)
            val tmp = File(target.parentFile, "${target.name}.tmp")
            val arr = JSONArray()
            for (proj in projects) {
                val obj = JSONObject().apply {
                    put("id", proj.id)
                    put("userId", proj.userId.ifBlank { userId })
                    put("name", proj.name)
                    put("aspectRatioPreset", proj.aspectRatioPreset)
                    put("width", proj.width)
                    put("height", proj.height)
                    put("fps", proj.fps)
                    put("durationMs", proj.durationMs)
                    put("codec", proj.codec)
                    put("bpm", proj.bpm)
                    put("layersPayload", ProjectPayloadSerializer.encodeProjectEnvelope(proj.layers, proj.backgroundColor))
                    put("beatMarkersPayload", ProjectPayloadSerializer.encodeBeatMarkers(proj.beatMarkers))
                    put("createdAtMs", timestampToMillis(proj.createdAt))
                    put("updatedAtMs", timestampToMillis(proj.updatedAt))
                }
                arr.put(obj)
            }
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
    }
}
