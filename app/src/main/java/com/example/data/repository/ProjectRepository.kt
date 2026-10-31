package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.error.OperationType
import com.example.data.error.handleFirestoreError
import com.example.data.model.CustomEffectPresetDocument
import com.example.data.model.EffectNode
import com.example.data.model.MotionProjectDocument
import com.example.data.model.Project
import com.example.data.model.ProjectPayloadSerializer
import com.example.data.model.toDocument
import com.example.data.model.toDomain
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.UUID

class ProjectRepository(private val db: FirebaseFirestore) {

    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private val auth = Firebase.auth

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    fun observeProjects(userId: String): Flow<List<Project>> {
        val path = "users/$userId/projects"
        return db.collection("users").document(userId).collection("projects")
            .whereEqualTo("userId", userId)
            .snapshots()
            .map { snapshot ->
                snapshot.documents.mapNotNull { doc ->
                    doc.toObject(
                        MotionProjectDocument::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )?.toDomain()
                }.sortedByDescending { it.updatedAt?.seconds ?: 0L }
            }
            .catch { error ->
                if (error is Exception && error !is CancellationException) {
                    handleFirestoreError(error, OperationType.LIST, path)
                }
                throw error
            }
    }

    suspend fun getProjectById(ownerUserId: String, projectId: String): Result<Project> {
        val docRef = db.collection("users").document(ownerUserId).collection("projects").document(projectId)
        return try {
            val snap = docRef.get().await()
            val doc = snap.toObject(
                MotionProjectDocument::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            ) ?: return Result.failure(NoSuchElementException("Project $projectId not found"))
            Result.success(doc.toDomain())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun createProject(project: Project): Result<String> {
        val uid = requireUserId()
        val projectId = project.id.ifBlank { "proj_${UUID.randomUUID().toString().replace("-", "").take(16)}" }
        val docRef = db.collection("users").document(uid).collection("projects").document(projectId)
        val payload = project.toDocument(uid).copy(
            id = projectId,
            createdAt = null,
            updatedAt = null
        )
        return try {
            docRef.set(payload).await()
            Result.success(projectId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun updateProject(project: Project): Result<Unit> {
        val uid = requireUserId()
        require(project.id.isNotBlank()) { "Project ID must not be blank for update" }
        val docRef = db.collection("users").document(uid).collection("projects").document(project.id)
        return try {
            val existingCreatedAt = project.createdAt ?: run {
                val existingSnap = docRef.get().await()
                existingSnap.getTimestamp("createdAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            }
            requireNotNull(existingCreatedAt) { "Project createdAt must not be null for update" }
            val payload = project.toDocument(uid).copy(
                createdAt = existingCreatedAt,
                updatedAt = null
            )
            docRef.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun deleteProject(projectId: String): Result<Unit> {
        val uid = requireUserId()
        val docRef = db.collection("users").document(uid).collection("projects").document(projectId)
        return try {
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            Result.failure(e)
        }
    }

    fun observePresets(userId: String): Flow<List<CustomEffectPresetDocument>> {
        val path = "users/$userId/presets"
        return db.collection("users").document(userId).collection("presets")
            .whereEqualTo("userId", userId)
            .snapshots()
            .map { snapshot ->
                snapshot.documents.mapNotNull { doc ->
                    doc.toObject(
                        CustomEffectPresetDocument::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }.sortedByDescending { it.updatedAt?.seconds ?: 0L }
            }
            .catch { error ->
                if (error is Exception && error !is CancellationException) {
                    handleFirestoreError(error, OperationType.LIST, path)
                }
                throw error
            }
    }

    suspend fun saveEffectPreset(name: String, category: String, effects: List<EffectNode>): Result<String> {
        val uid = requireUserId()
        val presetId = "preset_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val docRef = db.collection("users").document(uid).collection("presets").document(presetId)
        val payload = CustomEffectPresetDocument(
            id = presetId,
            userId = uid,
            name = name.take(80).ifBlank { "Custom Chain" },
            category = category.take(64).ifBlank { "Custom" },
            effectChainPayload = ProjectPayloadSerializer.encodeEffects(effects).toString(),
            createdAt = null,
            updatedAt = null
        )
        return try {
            docRef.set(payload).await()
            Result.success(presetId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }
}
