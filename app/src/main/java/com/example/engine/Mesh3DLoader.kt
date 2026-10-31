package com.example.engine

import android.content.Context
import android.net.Uri
import android.opengl.Matrix
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

data class Mesh3D(
    val name: String,
    val vertices: FloatArray, // x, y, z, nx, ny, nz, u, v (stride = 8 floats)
    val indices: ShortArray
)

object Mesh3DLoader {

    fun createCubeMesh(): Mesh3D = createBoxMesh(1f, 1f, 1f, "Cube")

    fun createBoxMesh(hx: Float = 1.35f, hy: Float = 0.85f, hz: Float = 0.75f, name: String = "Box"): Mesh3D {
        // 6 faces * 4 vertices = 24 vertices, 36 indices
        val faces = arrayOf(
            // Front (+Z)
            floatArrayOf(-hx, -hy, hz, 0f, 0f, 1f, 0f, 1f,  hx, -hy, hz, 0f, 0f, 1f, 1f, 1f,  hx, hy, hz, 0f, 0f, 1f, 1f, 0f,  -hx, hy, hz, 0f, 0f, 1f, 0f, 0f),
            // Back (-Z)
            floatArrayOf(hx, -hy, -hz, 0f, 0f, -1f, 0f, 1f,  -hx, -hy, -hz, 0f, 0f, -1f, 1f, 1f,  -hx, hy, -hz, 0f, 0f, -1f, 1f, 0f,  hx, hy, -hz, 0f, 0f, -1f, 0f, 0f),
            // Left (-X)
            floatArrayOf(-hx, -hy, -hz, -1f, 0f, 0f, 0f, 1f,  -hx, -hy, hz, -1f, 0f, 0f, 1f, 1f,  -hx, hy, hz, -1f, 0f, 0f, 1f, 0f,  -hx, hy, -hz, -1f, 0f, 0f, 0f, 0f),
            // Right (+X)
            floatArrayOf(hx, -hy, hz, 1f, 0f, 0f, 0f, 1f,  hx, -hy, -hz, 1f, 0f, 0f, 1f, 1f,  hx, hy, -hz, 1f, 0f, 0f, 1f, 0f,  hx, hy, hz, 1f, 0f, 0f, 0f, 0f),
            // Top (+Y)
            floatArrayOf(-hx, hy, hz, 0f, 1f, 0f, 0f, 1f,  hx, hy, hz, 0f, 1f, 0f, 1f, 1f,  hx, hy, -hz, 0f, 1f, 0f, 1f, 0f,  -hx, hy, -hz, 0f, 1f, 0f, 0f, 0f),
            // Bottom (-Y)
            floatArrayOf(-hx, -hy, -hz, 0f, -1f, 0f, 0f, 1f,  hx, -hy, -hz, 0f, -1f, 0f, 1f, 1f,  hx, -hy, hz, 0f, -1f, 0f, 1f, 0f,  -hx, -hy, hz, 0f, -1f, 0f, 0f, 0f)
        )
        val verts = FloatArray(24 * 8)
        val inds = ShortArray(36)
        faces.forEachIndexed { fIdx, fData ->
            System.arraycopy(fData, 0, verts, fIdx * 32, 32)
            val base = (fIdx * 4).toShort()
            val iOff = fIdx * 6
            inds[iOff] = base
            inds[iOff + 1] = (base + 1).toShort()
            inds[iOff + 2] = (base + 2).toShort()
            inds[iOff + 3] = base
            inds[iOff + 4] = (base + 2).toShort()
            inds[iOff + 5] = (base + 3).toShort()
        }
        return Mesh3D(name, verts, inds)
    }

    fun createPyramidMesh(): Mesh3D {
        val h = 1.15f
        val b = 1.0f
        val verts = floatArrayOf(
            // Front
            0f, h, 0f, 0f, 0.5f, 1f, 0.5f, 0f,
            -b, -h, b, 0f, 0.5f, 1f, 0f, 1f,
            b, -h, b, 0f, 0.5f, 1f, 1f, 1f,
            // Right
            0f, h, 0f, 1f, 0.5f, 0f, 0.5f, 0f,
            b, -h, b, 1f, 0.5f, 0f, 0f, 1f,
            b, -h, -b, 1f, 0.5f, 0f, 1f, 1f,
            // Back
            0f, h, 0f, 0f, 0.5f, -1f, 0.5f, 0f,
            b, -h, -b, 0f, 0.5f, -1f, 0f, 1f,
            -b, -h, -b, 0f, 0.5f, -1f, 1f, 1f,
            // Left
            0f, h, 0f, -1f, 0.5f, 0f, 0.5f, 0f,
            -b, -h, -b, -1f, 0.5f, 0f, 0f, 1f,
            -b, -h, b, -1f, 0.5f, 0f, 1f, 1f
        )
        val inds = ShortArray(12) { it.toShort() }
        return Mesh3D("Pyramid", verts, inds)
    }

    fun createTorusMesh(majorR: Float = 0.9f, minorR: Float = 0.36f, segU: Int = 24, segV: Int = 14): Mesh3D {
        val vertCount = (segU + 1) * (segV + 1)
        val verts = FloatArray(vertCount * 8)
        val inds = ShortArray(segU * segV * 6)
        var vPtr = 0
        for (i in 0..segU) {
            val u = (i.toFloat() / segU) * (2f * PI.toFloat())
            val cu = cos(u)
            val su = sin(u)
            for (j in 0..segV) {
                val v = (j.toFloat() / segV) * (2f * PI.toFloat())
                val cv = cos(v)
                val sv = sin(v)
                val x = (majorR + minorR * cv) * cu
                val y = (majorR + minorR * cv) * su
                val z = minorR * sv
                val nx = cv * cu
                val ny = cv * su
                val nz = sv
                verts[vPtr++] = x
                verts[vPtr++] = y
                verts[vPtr++] = z
                verts[vPtr++] = nx
                verts[vPtr++] = ny
                verts[vPtr++] = nz
                verts[vPtr++] = i.toFloat() / segU
                verts[vPtr++] = j.toFloat() / segV
            }
        }
        var iPtr = 0
        for (i in 0 until segU) {
            for (j in 0 until segV) {
                val p0 = (i * (segV + 1) + j).toShort()
                val p1 = ((i + 1) * (segV + 1) + j).toShort()
                val p2 = ((i + 1) * (segV + 1) + j + 1).toShort()
                val p3 = (i * (segV + 1) + j + 1).toShort()
                inds[iPtr++] = p0; inds[iPtr++] = p1; inds[iPtr++] = p2
                inds[iPtr++] = p0; inds[iPtr++] = p2; inds[iPtr++] = p3
            }
        }
        return Mesh3D("Torus", verts, inds)
    }

    /**
     * Parses Wavefront .obj files or falls back to procedural Torus/Crystal mesh for .gltf/.glb/custom URIs.
     */
    fun loadMeshFromUri(context: Context, uri: Uri): Mesh3D {
        return try {
            val posList = mutableListOf<FloatArray>()
            val outVerts = mutableListOf<Float>()
            val outInds = mutableListOf<Short>()

            context.contentResolver.openInputStream(uri)?.use { stream ->
                BufferedReader(InputStreamReader(stream)).forEachLine { raw ->
                    val line = raw.trim()
                    when {
                        line.startsWith("v ") -> {
                            val parts = line.split(Regex("\\s+"))
                            if (parts.size >= 4) {
                                posList.add(
                                    floatArrayOf(
                                        parts[1].toFloatOrNull() ?: 0f,
                                        parts[2].toFloatOrNull() ?: 0f,
                                        parts[3].toFloatOrNull() ?: 0f
                                    )
                                )
                            }
                        }
                        line.startsWith("f ") && outInds.size < 9000 -> {
                            val parts = line.split(Regex("\\s+")).drop(1)
                            val faceIndices = parts.mapNotNull { token ->
                                val idx = token.substringBefore("/").toIntOrNull()
                                if (idx != null && idx >= 1 && idx <= posList.size) posList[idx - 1] else null
                            }
                            if (faceIndices.size >= 3) {
                                for (t in 1 until faceIndices.size - 1) {
                                    val tri = listOf(faceIndices[0], faceIndices[t], faceIndices[t + 1])
                                    tri.forEachIndexed { k, p ->
                                        val vertIdx = (outVerts.size / 8).toShort()
                                        outVerts.add(p[0]); outVerts.add(p[1]); outVerts.add(p[2])
                                        outVerts.add(0f); outVerts.add(0.7f); outVerts.add(0.7f)
                                        outVerts.add(if (k == 1) 1f else 0f); outVerts.add(if (k == 2) 1f else 0f)
                                        outInds.add(vertIdx)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (outVerts.isNotEmpty() && outInds.isNotEmpty()) {
                Mesh3D("Imported OBJ Mesh", outVerts.toFloatArray(), outInds.toShortArray())
            } else {
                createTorusMesh(majorR = 0.95f, minorR = 0.38f, segU = 32, segV = 18)
            }
        } catch (_: Exception) {
            createTorusMesh(majorR = 0.95f, minorR = 0.38f, segU = 32, segV = 18)
        }
    }

    /**
     * Computes 4x4 Model * View * Projection matrix for 3D camera + layer transform.
     */
    fun computeMvpMatrix(
        aspectRatio: Float,
        camYawDeg: Float,
        camPitchDeg: Float,
        camDistance: Float,
        layerPosX: Float,
        layerPosY: Float,
        layerPosZ: Float,
        layerRotX: Float,
        layerRotY: Float,
        layerRotZ: Float,
        layerScale: Float
    ): FloatArray {
        val proj = FloatArray(16)
        val view = FloatArray(16)
        val model = FloatArray(16)
        val vp = FloatArray(16)
        val mvp = FloatArray(16)

        Matrix.perspectiveM(proj, 0, 45f, aspectRatio.coerceAtLeast(0.1f), 0.1f, 100f)

        val yawRad = Math.toRadians(camYawDeg.toDouble())
        val pitchRad = Math.toRadians(camPitchDeg.coerceIn(-80f, 80f).toDouble())
        val dist = camDistance.coerceIn(1.8f, 18f)
        val eyeX = (dist * cos(pitchRad) * sin(yawRad)).toFloat()
        val eyeY = (dist * sin(pitchRad)).toFloat()
        val eyeZ = (dist * cos(pitchRad) * cos(yawRad)).toFloat()

        Matrix.setLookAtM(view, 0, eyeX, eyeY, eyeZ, 0f, 0f, 0f, 0f, 1f, 0f)

        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, layerPosX / 500f, -layerPosY / 500f, layerPosZ / 500f)
        Matrix.rotateM(model, 0, layerRotX, 1f, 0f, 0f)
        Matrix.rotateM(model, 0, layerRotY, 0f, 1f, 0f)
        Matrix.rotateM(model, 0, layerRotZ, 0f, 0f, 1f)
        Matrix.scaleM(model, 0, layerScale, layerScale, layerScale)

        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
        return mvp
    }
}
