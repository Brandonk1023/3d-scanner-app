package app.facet.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.facet.data.Scan
import app.facet.data.ScanRepository
import app.facet.data.ScanStatus
import app.facet.mesh.Mesh
import app.facet.scan.ScanTuning
import app.facet.settings.LengthUnit
import app.facet.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

enum class GalleryFilter { ALL, HIGH_DETAIL, QUICK, PROCESSING }

/** Date section headers. The UI formats [Month] with the device locale. */
sealed interface SectionHeader {
    data object Today : SectionHeader
    data object ThisWeek : SectionHeader
    data class Month(val year: Int, val month: Int) : SectionHeader
}

data class GallerySection(val header: SectionHeader, val scans: List<Scan>)

data class GalleryUiState(
    val loading: Boolean = true,
    /** False → the empty state ("No scans yet"), regardless of search/filter. */
    val hasAnyScans: Boolean = false,
    val query: String = "",
    val filter: GalleryFilter = GalleryFilter.ALL,
    /** Filtered + searched scans, newest first, grouped by date. Empty with [hasAnyScans] → "no matches". */
    val sections: List<GallerySection> = emptyList(),
    val units: LengthUnit = LengthUnit.MILLIMETRES,
    val highDetailEstimateMin: Int = ScanTuning.Default.reconstruction.highDetailEstimateMin,
)

class GalleryViewModel(
    private val repository: ScanRepository,
    settings: SettingsRepository,
    private val clock: Clock = Clock.System,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(GalleryFilter.ALL)

    val state: StateFlow<GalleryUiState> =
        combine(repository.scans, settings.settings, query, filter) { scans, s, q, f ->
            GalleryUiState(
                loading = false,
                hasAnyScans = scans.isNotEmpty(),
                query = q,
                filter = f,
                sections = groupScansByDate(scans.filtered(q, f), clock.now(), timeZone),
                units = s.units,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, GalleryUiState())

    fun setQuery(value: String) { query.value = value }

    fun setFilter(value: GalleryFilter) { filter.value = value }

    fun rename(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { repository.rename(id, trimmed) }
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.delete(id) }
    }

    /** Reduced mesh for the card thumbnail. */
    suspend fun mesh(id: String): Mesh? = repository.loadThumbnail(id)
}

fun List<Scan>.filtered(query: String, filter: GalleryFilter): List<Scan> {
    val q = query.trim()
    return filter { scan ->
        val statusOk = when (filter) {
            GalleryFilter.ALL -> true
            GalleryFilter.HIGH_DETAIL -> scan.status == ScanStatus.HIGH_DETAIL
            GalleryFilter.QUICK -> scan.status == ScanStatus.QUICK
            GalleryFilter.PROCESSING -> scan.status == ScanStatus.PROCESSING
        }
        statusOk && (q.isEmpty() || scan.name.contains(q, ignoreCase = true))
    }
}

/** Today, This week (the 6 days before today), then one section per calendar month. Newest first. */
fun groupScansByDate(scans: List<Scan>, now: Instant, timeZone: TimeZone): List<GallerySection> {
    val today = now.toLocalDateTime(timeZone).date
    val weekStart = today.minus(6, DateTimeUnit.DAY)
    val sections = LinkedHashMap<SectionHeader, MutableList<Scan>>()
    scans.sortedByDescending { it.createdAt }.forEach { scan ->
        val date = scan.createdAt.toLocalDateTime(timeZone).date
        val header = when {
            date >= today -> SectionHeader.Today
            date >= weekStart -> SectionHeader.ThisWeek
            else -> SectionHeader.Month(date.year, date.month.ordinal + 1)
        }
        sections.getOrPut(header) { mutableListOf() } += scan
    }
    return sections.map { (header, list) -> GallerySection(header, list) }
}
