package app.facet.data

import app.facet.data.db.ScanDao
import app.facet.data.db.ScanEntity
import app.facet.geometry.BoxSize
import app.facet.mesh.Mesh
import app.facet.mesh.SampleMeshes
import app.facet.mesh.SampleShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** In-memory [ScanDao] with the same queries as the Room one. */
private class FakeScanDao : ScanDao {
    val rows = MutableStateFlow<Map<String, ScanEntity>>(emptyMap())
    override fun observeAll(): Flow<List<ScanEntity>> = rows.map { m -> m.values.sortedByDescending { it.createdAtMs } }
    override fun observe(id: String): Flow<ScanEntity?> = rows.map { it[id] }
    override suspend fun get(id: String): ScanEntity? = rows.value[id]
    override suspend fun names(): List<String> = rows.value.values.map { it.name }
    override suspend fun upsert(scan: ScanEntity) = rows.update { it + (scan.id to scan) }
    override suspend fun rename(id: String, name: String) = rows.update { m -> m[id]?.let { m + (id to it.copy(name = name)) } ?: m }
    override suspend fun delete(id: String) = rows.update { it - id }
    override suspend fun idsWithClearablePhotos(): List<String> =
        rows.value.values.filter { it.hasSavedPhotos && it.processingPercent == null }.map { it.id }
    override suspend fun markPhotosCleared(ids: List<String>) = rows.update { m ->
        m.mapValues { (id, e) -> if (id in ids) e.copy(hasSavedPhotos = false, photoBytes = 0) else e }
    }
}

class RoomScanRepositoryTest {
    private val fs = FakeFileSystem()
    private val dao = FakeScanDao()
    private val root = "/files/scans".toPath()
    private var thumbnailCalls = 0
    private val repo = RoomScanRepository(dao, fs, root, makeThumbnail = { thumbnailCalls++; tiny }, io = Dispatchers.Unconfined)

    private val tiny = Mesh(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f), intArrayOf(0, 1, 2))

    private fun scan(id: String, name: String = id, photos: Boolean = true, processing: Int? = null) = Scan(
        id = id, name = name, createdAt = Instant.fromEpochMilliseconds(1_000), quality = ResultQuality.QUICK,
        processingPercent = processing, size = BoxSize(10.0, 20.0, 30.0), triangleCount = 1,
        hasSavedPhotos = photos, photoBytes = if (photos) 500 else 0,
    )

    @AfterTest fun noLeaks() = fs.checkNoOpenFiles()

    @Test
    fun addWritesMeshesAndRecordAndLoadsThemBack() = runTest {
        val mug = SampleMeshes.create(SampleShape.MUG)
        repo.add(scan("scan-1"), mug)

        val saved = assertNotNull(repo.scan("scan-1").first())
        assertEquals(MeshFiles.byteSize(mug) + MeshFiles.byteSize(tiny), saved.modelBytes)
        assertEquals(BoxSize(10.0, 20.0, 30.0), saved.size)
        val loaded = assertNotNull(repo.loadMesh("scan-1"))
        assertContentEquals(mug.positions, loaded.positions)
        assertContentEquals(mug.indices, loaded.indices)
        // Big meshes get a reduced thumbnail; small ones are their own thumbnail.
        assertEquals(if (mug.triangleCount > RoomScanRepository.THUMBNAIL_TRIANGLES) 1 else 0, thumbnailCalls)
        assertNotNull(repo.loadThumbnail("scan-1"))
    }

    @Test
    fun thumbnailUsedOnlyAboveTheLimit() = runTest {
        val big = Mesh(FloatArray(9), IntArray(3 * (RoomScanRepository.THUMBNAIL_TRIANGLES + 1)))
        repo.add(scan("big"), big)
        assertEquals(1, thumbnailCalls)
        assertEquals(1, repo.loadThumbnail("big")!!.triangleCount)
        assertEquals(RoomScanRepository.THUMBNAIL_TRIANGLES + 1, repo.loadMesh("big")!!.triangleCount)
    }

    @Test
    fun deleteRemovesRecordAndFolder() = runTest {
        repo.add(scan("scan-1"), tiny)
        fs.createDirectories(root / "scan-1" / "keyframes")
        repo.delete("scan-1")
        assertNull(repo.scan("scan-1").first())
        assertFalse(fs.exists(root / "scan-1"))
        assertNull(repo.loadMesh("scan-1"))
    }

    @Test
    fun clearSavedPhotosSkipsScansStillProcessing() = runTest {
        repo.add(scan("done"), tiny)
        repo.add(scan("busy", processing = 40), tiny)
        listOf("done", "busy").forEach { fs.createDirectories(root / it / "keyframes") }

        repo.clearSavedPhotos()

        assertFalse(fs.exists(root / "done" / "keyframes"))
        assertTrue(fs.exists(root / "done" / RoomScanRepository.MODEL_FILE))
        assertTrue(fs.exists(root / "busy" / "keyframes"))
        assertFalse(repo.scan("done").first()!!.hasSavedPhotos)
        assertTrue(repo.scan("busy").first()!!.hasSavedPhotos)
        assertEquals(500L, repo.usage.first().savedPhotoBytes)
    }

    @Test
    fun namesAndRename() = runTest {
        assertEquals("Scan_01", repo.nextScanName())
        repo.add(scan("a", name = "Scan_01"), tiny)
        repo.add(scan("b", name = "Scan_02"), tiny)
        assertEquals("Scan_03", repo.nextScanName())
        repo.rename("a", "Lid")
        assertEquals("Scan_01", repo.nextScanName())
        assertEquals("Lid", repo.scan("a").first()!!.name)
    }

    @Test
    fun rejectsIdsThatEscapeTheScansFolder() = runTest {
        assertFailsWith<IllegalArgumentException> { repo.add(scan("../evil"), tiny) }
        assertFailsWith<IllegalArgumentException> { repo.delete("..") }
    }

    @Test
    fun samplesCanBeAddedToARealGallery() = runTest {
        repo.addSampleScans()
        val scans = repo.scans.first()
        assertEquals(5, scans.size)
        assertTrue(scans.none { it.hasSavedPhotos })
        scans.forEach { assertNotNull(repo.loadMesh(it.id), it.id) }
    }
}

class MeshFilesTest {
    @Test
    fun roundTrip() {
        val gear = SampleMeshes.create(SampleShape.GEAR)
        val buffer = Buffer()
        MeshFiles.write(buffer, gear)
        assertEquals(MeshFiles.byteSize(gear), buffer.size)
        val back = MeshFiles.read(buffer)
        assertContentEquals(gear.positions, back.positions)
        assertContentEquals(gear.indices, back.indices)
    }

    @Test
    fun corruptFilesReadAsMissing() {
        val fs = FakeFileSystem()
        val path = "/m.fmesh".toPath()
        fs.write(path) { writeUtf8("not a mesh") }
        assertNull(MeshFiles.read(fs, path))

        // Index pointing past the vertices.
        val bad = Buffer()
        MeshFiles.write(bad, Mesh(FloatArray(9), intArrayOf(0, 1, 2)))
        val bytes = bad.readByteArray()
        bytes[bytes.size - 4] = 7
        fs.write(path) { write(bytes) }
        assertNull(MeshFiles.read(fs, path))
        assertNull(MeshFiles.read(fs, "/missing.fmesh".toPath()))
    }
}
