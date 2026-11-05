package app.facet.data

import app.facet.mesh.Mesh
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.buffer
import okio.use

/**
 * The app's own mesh file (`*.fmesh`): what the gallery keeps on disk for each scan. Not an export
 * format — Save / Share write STL or 3MF from it.
 *
 * Little-endian: magic "FMSH", version (int), vertex count (int), triangle count (int), then
 * xyz floats per vertex and three int indices per triangle.
 */
object MeshFiles {
    private const val MAGIC = 0x48534D46 // "FMSH" read as a little-endian int
    private const val VERSION = 1
    private const val HEADER_BYTES = 16L

    fun byteSize(mesh: Mesh): Long = HEADER_BYTES + 4L * mesh.positions.size + 4L * mesh.indices.size

    /** Writes to a temporary file first, so a crash never leaves a half-written mesh behind. */
    fun write(fs: FileSystem, path: Path, mesh: Mesh) {
        path.parent?.let(fs::createDirectories)
        val tmp = path.parent!! / (path.name + ".tmp")
        fs.sink(tmp).buffer().use { write(it, mesh) }
        fs.atomicMove(tmp, path)
    }

    /** Null when the file is missing or isn't a valid mesh file. */
    fun read(fs: FileSystem, path: Path): Mesh? {
        if (!fs.exists(path)) return null
        return try {
            fs.source(path).buffer().use(::read)
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    fun write(sink: BufferedSink, mesh: Mesh) {
        sink.writeIntLe(MAGIC)
        sink.writeIntLe(VERSION)
        sink.writeIntLe(mesh.vertexCount)
        sink.writeIntLe(mesh.triangleCount)
        // Chunked through a Buffer: one sink call per value is slow for 200k-triangle meshes.
        val chunk = Buffer()
        mesh.positions.forEach { chunk.writeIntLe(it.toRawBits()); if (chunk.size >= CHUNK) sink.write(chunk, chunk.size) }
        mesh.indices.forEach { chunk.writeIntLe(it); if (chunk.size >= CHUNK) sink.write(chunk, chunk.size) }
        sink.write(chunk, chunk.size)
    }

    fun read(source: BufferedSource): Mesh {
        require(source.readIntLe() == MAGIC) { "not a Facet mesh file" }
        val version = source.readIntLe()
        require(version == VERSION) { "unsupported mesh file version $version" }
        val vertices = source.readIntLe()
        val triangles = source.readIntLe()
        require(vertices in 0..MAX_COUNT && triangles in 0..MAX_COUNT) { "corrupt mesh header" }
        val positions = FloatArray(vertices * 3) { Float.fromBits(source.readIntLe()) }
        val indices = IntArray(triangles * 3) { source.readIntLe() }
        require(indices.all { it in 0 until vertices }) { "index out of range" }
        return Mesh(positions, indices)
    }

    private const val CHUNK = 64L * 1024
    private const val MAX_COUNT = 50_000_000
}
