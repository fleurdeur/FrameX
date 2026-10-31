package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.EffectNode
import com.example.data.model.Layer
import com.example.data.model.LayerType
import com.example.data.model.Project
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ProjectRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun createUpdateObserveAndDeleteProject_authenticatedOwner_succeeds() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val repository = ProjectRepository(firestore)

        val initialProject = Project(
            name = "Cyber Tunnel 60FPS",
            aspectRatioPreset = "9:16",
            width = 1080,
            height = 1920,
            fps = 60,
            durationMs = 12000L,
            codec = "H.264",
            bpm = 140,
            layers = listOf(
                Layer(
                    name = "Neon Grid",
                    type = LayerType.SHAPE,
                    effects = listOf(EffectNode(effectId = "S_Glow", displayName = "S_Glow"))
                )
            )
        )

        val createResult = withTimeout(DEFAULT_TIMEOUT_MS) { repository.createProject(initialProject) }
        assertTrue("Expected project creation to succeed: ${createResult.exceptionOrNull()}", createResult.isSuccess)
        val createdId = createResult.getOrThrow()
        assertTrue(createdId.isNotEmpty())

        val observed = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeProjects(aliceUid).first { list -> list.any { it.id == createdId } }
        }
        val fetched = observed.first { it.id == createdId }
        assertEquals("Cyber Tunnel 60FPS", fetched.name)
        assertEquals(60, fetched.fps)

        val updateResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.updateProject(fetched.copy(name = "Cyber Tunnel 120FPS", fps = 120))
        }
        assertTrue("Expected update to succeed: ${updateResult.exceptionOrNull()}", updateResult.isSuccess)

        val afterUpdate = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.getProjectById(aliceUid, createdId).getOrThrow()
        }
        assertEquals("Cyber Tunnel 120FPS", afterUpdate.name)
        assertEquals(120, afterUpdate.fps)

        val deleteResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.deleteProject(createdId)
        }
        assertTrue(deleteResult.isSuccess)
    }

    @Test
    fun getProjectById_crossUserAccess_failsWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val aliceRepo = ProjectRepository(firestore)
        val createdId = withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.createProject(Project(name = "Alice Secret Project")).getOrThrow()
        }

        signInTestUser(BOB_EMAIL)
        val bobRepo = ProjectRepository(firestore)
        val crossReadResult = withTimeout(DEFAULT_TIMEOUT_MS) {
            bobRepo.getProjectById(aliceUid, createdId)
        }
        assertTrue(crossReadResult.isFailure)
        val fsEx = crossReadResult.exceptionOrNull()?.unwrapFirestoreException()
        assertNotNull("Expected FirebaseFirestoreException", fsEx)
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsEx?.code)
    }

    @Test
    fun observeProjects_unauthenticatedUser_failsWithPermissionDenied() = runBlocking {
        auth.signOut()
        val repository = ProjectRepository(firestore)

        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                repository.observeProjects("unauth_target_uid").first()
            }
            fail("Expected unauthenticated observeProjects to throw PERMISSION_DENIED")
        } catch (e: Exception) {
            val fsEx = e.unwrapFirestoreException()
            assertNotNull("Expected wrapped FirebaseFirestoreException, got $e", fsEx)
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsEx?.code)
        }
    }

    @Test
    fun saveAndObserveEffectPreset_authenticatedOwner_succeeds() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val repository = ProjectRepository(firestore)

        val presetId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.saveEffectPreset(
                name = "Sapphire Glow Stack",
                category = "Desktop Suite Homages",
                effects = listOf(EffectNode(effectId = "S_Glow", displayName = "S_Glow"))
            ).getOrThrow()
        }

        val presets = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observePresets(aliceUid).first { list -> list.any { it.id == presetId } }
        }
        assertTrue(presets.any { it.id == presetId && it.name == "Sapphire Glow Stack" })
    }

    private companion object {
        const val ALICE_EMAIL = "alice_fp@test.com"
        const val BOB_EMAIL = "bob_fp@test.com"
        const val DEFAULT_TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 4000L
    }
}
