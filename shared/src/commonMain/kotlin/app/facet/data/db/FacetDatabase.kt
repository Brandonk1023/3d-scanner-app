package app.facet.data.db

import androidx.room.ConstructedBy
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.Upsert
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.facet.data.ResultQuality
import app.facet.data.Scan
import app.facet.geometry.BoxSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/** File name of the gallery database (Android: in the app's databases folder). */
const val DATABASE_FILE_NAME = "facet.db"

/**
 * The gallery. Only the scan records live here; each scan's mesh and keyframes are files in its own
 * folder (see `RoomScanRepository`).
 */
@Database(entities = [ScanEntity::class], version = 1, exportSchema = true)
@ConstructedBy(FacetDatabaseConstructor::class)
abstract class FacetDatabase : RoomDatabase() {
    abstract fun scans(): ScanDao
}

/** Implemented by Room's code generator for each target. */
@Suppress("KotlinNoActualForExpect")
expect object FacetDatabaseConstructor : RoomDatabaseConstructor<FacetDatabase> {
    override fun initialize(): FacetDatabase
}

/** Shared builder settings; the platform code supplies the builder (it needs a Context on Android). */
fun RoomDatabase.Builder<FacetDatabase>.buildFacetDatabase(): FacetDatabase =
    setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()

@Entity(tableName = "scans")
data class ScanEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAtMs: Long,
    /** [ResultQuality] name. */
    val quality: String,
    val processingPercent: Int?,
    val widthMm: Double,
    val depthMm: Double,
    val heightMm: Double,
    val triangleCount: Int,
    val scaleReference: String?,
    val hasSavedPhotos: Boolean,
    val modelBytes: Long,
    val photoBytes: Long,
)

@Dao
interface ScanDao {
    @Query("SELECT * FROM scans ORDER BY createdAtMs DESC")
    fun observeAll(): Flow<List<ScanEntity>>

    @Query("SELECT * FROM scans WHERE id = :id")
    fun observe(id: String): Flow<ScanEntity?>

    @Query("SELECT * FROM scans WHERE id = :id")
    suspend fun get(id: String): ScanEntity?

    @Query("SELECT name FROM scans")
    suspend fun names(): List<String>

    @Upsert
    suspend fun upsert(scan: ScanEntity)

    @Query("UPDATE scans SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)

    @Query("DELETE FROM scans WHERE id = :id")
    suspend fun delete(id: String)

    /** Scans whose keyframes can be cleared: not while High detail is using them. */
    @Query("SELECT id FROM scans WHERE hasSavedPhotos = 1 AND processingPercent IS NULL")
    suspend fun idsWithClearablePhotos(): List<String>

    @Query("UPDATE scans SET hasSavedPhotos = 0, photoBytes = 0 WHERE id IN (:ids)")
    suspend fun markPhotosCleared(ids: List<String>)
}

fun ScanEntity.toScan(): Scan = Scan(
    id = id,
    name = name,
    createdAt = Instant.fromEpochMilliseconds(createdAtMs),
    quality = ResultQuality.entries.firstOrNull { it.name == quality } ?: ResultQuality.QUICK,
    processingPercent = processingPercent,
    size = BoxSize(widthMm, depthMm, heightMm),
    triangleCount = triangleCount,
    scaleReference = scaleReference,
    hasSavedPhotos = hasSavedPhotos,
    modelBytes = modelBytes,
    photoBytes = photoBytes,
)

fun Scan.toEntity(): ScanEntity = ScanEntity(
    id = id,
    name = name,
    createdAtMs = createdAt.toEpochMilliseconds(),
    quality = quality.name,
    processingPercent = processingPercent,
    widthMm = size.widthMm,
    depthMm = size.depthMm,
    heightMm = size.heightMm,
    triangleCount = triangleCount,
    scaleReference = scaleReference,
    hasSavedPhotos = hasSavedPhotos,
    modelBytes = modelBytes,
    photoBytes = photoBytes,
)
