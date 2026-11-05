package app.facet.android.ar

import android.opengl.GLES30
import android.opengl.Matrix
import app.facet.mesh.Mesh
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Draws the live fused mesh over the camera (A-Capture.png): lime triangle wireframe over a 16%
 * lime fill. Triangles are expanded with barycentric coordinates so the wireframe is drawn in the
 * fragment shader. GL thread only.
 */
internal class LiveMeshRenderer {
    private var program = 0
    private var vbo = 0
    private var uMvp = 0
    private var uFill = 0
    private var uLine = 0
    private var vertexCount = 0

    private class Upload(val buffer: FloatBuffer, val vertices: Int)

    @Volatile private var pending: Upload? = null
    private var uploaded: Upload? = null

    /** Any thread: replaces the mesh shown from the next frame (box-frame mm). */
    fun submit(mesh: Mesh) {
        val tris = mesh.triangleCount
        val buf = ByteBuffer.allocateDirect(tris * 3 * FLOATS_PER_VERTEX * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val p = mesh.positions
        val idx = mesh.indices
        for (t in 0 until tris) {
            for (corner in 0 until 3) {
                val v = idx[t * 3 + corner] * 3
                buf.put(p[v]).put(p[v + 1]).put(p[v + 2])
                buf.put(if (corner == 0) 1f else 0f).put(if (corner == 1) 1f else 0f).put(if (corner == 2) 1f else 0f)
            }
        }
        buf.position(0)
        pending = Upload(buf, tris * 3)
    }

    fun clear() {
        pending = Upload(ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asFloatBuffer(), 0)
    }

    /** onSurfaceCreated (also after a lost context). */
    fun create() {
        program = link(VERTEX, FRAGMENT)
        uMvp = GLES30.glGetUniformLocation(program, "uMvp")
        uFill = GLES30.glGetUniformLocation(program, "uFill")
        uLine = GLES30.glGetUniformLocation(program, "uLine")
        val b = IntArray(1)
        GLES30.glGenBuffers(1, b, 0)
        vbo = b[0]
        vertexCount = 0
        // A new GL context lost the old buffer: upload the last mesh again.
        if (pending == null) pending = uploaded
    }

    /** [alpha] dims the whole mesh (40% while tracking is lost). */
    fun draw(view: FloatArray, projection: FloatArray, worldFromBox: FloatArray, alpha: Float) {
        pending?.let { up ->
            pending = null
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, up.buffer.capacity() * 4, up.buffer, GLES30.GL_DYNAMIC_DRAW)
            vertexCount = up.vertices
            uploaded = up
        }
        if (vertexCount == 0) return

        val mv = FloatArray(16)
        val mvp = FloatArray(16)
        Matrix.multiplyMM(mv, 0, view, 0, worldFromBox, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)

        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES30.glUniform4f(uFill, GUIDE_R, GUIDE_G, GUIDE_B, 0.16f * alpha)
        GLES30.glUniform4f(uLine, GUIDE_R, GUIDE_G, GUIDE_B, 0.9f * alpha)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        // Front faces only, nearest surface wins: reads as a skin on the object.
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_BACK)
        GLES30.glFrontFace(GLES30.GL_CCW)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, FLOATS_PER_VERTEX * 4, 0)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, FLOATS_PER_VERTEX * 4, 3 * 4)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, vertexCount)
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun link(vs: String, fs: String): Int = GLES30.glCreateProgram().also { p ->
        GLES30.glAttachShader(p, compile(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, compile(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "Mesh shader link failed: ${GLES30.glGetProgramInfoLog(p)}" }
    }

    private fun compile(type: Int, source: String): Int = GLES30.glCreateShader(type).also { s ->
        GLES30.glShaderSource(s, source)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { "Mesh shader compile failed: ${GLES30.glGetShaderInfoLog(s)}" }
    }

    private companion object {
        const val FLOATS_PER_VERTEX = 6

        // ArColors.guide #D4F54A
        const val GUIDE_R = 0xD4 / 255f
        const val GUIDE_G = 0xF5 / 255f
        const val GUIDE_B = 0x4A / 255f

        const val VERTEX = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aBary;
uniform mat4 uMvp;
out vec3 vBary;
void main() {
    gl_Position = uMvp * vec4(aPos, 1.0);
    vBary = aBary;
}
"""

        const val FRAGMENT = """#version 300 es
precision mediump float;
in vec3 vBary;
uniform vec4 uFill;
uniform vec4 uLine;
out vec4 outColor;
void main() {
    vec3 width = fwidth(vBary) * 1.2;
    vec3 a = smoothstep(vec3(0.0), width, vBary);
    float edge = 1.0 - min(min(a.x, a.y), a.z);
    outColor = mix(uFill, uLine, edge);
}
"""
    }
}
