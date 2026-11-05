package app.facet.ui

import app.facet.data.FakeScanRepository
import app.facet.data.ResultQuality
import app.facet.data.Scan
import app.facet.data.ScanStatus
import app.facet.geometry.BoxSize
import app.facet.settings.InMemorySettingsRepository
import app.facet.ui.gallery.GalleryFilter
import app.facet.ui.gallery.GalleryViewModel
import app.facet.ui.gallery.SectionHeader
import app.facet.ui.gallery.filtered
import app.facet.ui.gallery.groupScansByDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class GalleryTest {
    private val tz = TimeZone.UTC
    // Wednesday 2026-11-04 15:00 UTC
    private val now = LocalDateTime(2026, 11, 4, 15, 0).toInstant(tz)

    private fun scan(name: String, at: Instant, quality: ResultQuality = ResultQuality.QUICK, processing: Int? = null) =
        Scan(name, name, at, quality, processing, BoxSize(1.0, 1.0, 1.0), 12)

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun groupsTodayThisWeekThenMonths() {
        val scans = listOf(
            scan("old", LocalDateTime(2026, 9, 2, 10, 0).toInstant(tz)),
            scan("today-early", LocalDateTime(2026, 11, 4, 0, 30).toInstant(tz)),
            scan("six-days", LocalDateTime(2026, 10, 29, 9, 0).toInstant(tz)),
            scan("seven-days", LocalDateTime(2026, 10, 28, 23, 0).toInstant(tz)),
            scan("today-late", LocalDateTime(2026, 11, 4, 14, 0).toInstant(tz)),
        )
        val sections = groupScansByDate(scans, now, tz)
        assertEquals(
            listOf(SectionHeader.Today, SectionHeader.ThisWeek, SectionHeader.Month(2026, 10), SectionHeader.Month(2026, 9)),
            sections.map { it.header },
        )
        assertEquals(listOf("today-late", "today-early"), sections[0].scans.map { it.name })
        assertEquals(listOf("six-days"), sections[1].scans.map { it.name })
        assertEquals(listOf("seven-days"), sections[2].scans.map { it.name })
    }

    @Test
    fun filterUsesOneStatusPerScan() {
        val scans = listOf(
            scan("Quick", now),
            scan("High", now, ResultQuality.HIGH_DETAIL),
            scan("Upgrading", now, ResultQuality.QUICK, processing = 40),
        )
        assertEquals(ScanStatus.PROCESSING, scans[2].status)
        assertEquals(listOf("Quick"), scans.filtered("", GalleryFilter.QUICK).map { it.name })
        assertEquals(listOf("High"), scans.filtered("", GalleryFilter.HIGH_DETAIL).map { it.name })
        assertEquals(listOf("Upgrading"), scans.filtered("", GalleryFilter.PROCESSING).map { it.name })
        assertEquals(3, scans.filtered("", GalleryFilter.ALL).size)
    }

    @Test
    fun searchMatchesNameIgnoringCaseAndSpaces() {
        val scans = listOf(scan("Mug_01", now), scan("Bracket_L", now))
        assertEquals(listOf("Mug_01"), scans.filtered("  mUG ", GalleryFilter.ALL).map { it.name })
        assertTrue(scans.filtered("gear", GalleryFilter.ALL).isEmpty())
    }

    @Test
    fun viewModelSearchesRenamesAndDeletes() = runTest {
        val repo = FakeScanRepository()
        val vm = GalleryViewModel(repo, InMemorySettingsRepository(), timeZone = tz)
        val all = vm.state.value.sections.sumOf { it.scans.size }
        assertTrue(all >= 3)

        vm.setQuery("mug")
        assertEquals(listOf("Mug_01"), vm.state.value.sections.flatMap { it.scans }.map { it.name })

        vm.rename("mug-01", "  Coffee mug ")
        vm.setQuery("coffee")
        assertEquals(listOf("Coffee mug"), vm.state.value.sections.flatMap { it.scans }.map { it.name })

        vm.rename("mug-01", "   ") // blank names are ignored
        assertEquals(listOf("Coffee mug"), vm.state.value.sections.flatMap { it.scans }.map { it.name })

        vm.delete("mug-01")
        assertTrue(vm.state.value.sections.isEmpty())
        assertTrue(vm.state.value.hasAnyScans) // other scans still exist → "no matches", not empty state

        vm.setQuery("")
        assertEquals(all - 1, vm.state.value.sections.sumOf { it.scans.size })
    }

    @Test
    fun clearingPhotosDisablesReprocess() = runTest {
        val repo = FakeScanRepository()
        val vm = GalleryViewModel(repo, InMemorySettingsRepository(), timeZone = tz)
        val mug = vm.state.value.sections.flatMap { it.scans }.first { it.id == "mug-01" }
        assertTrue(mug.canReprocess)
        repo.clearSavedPhotos()
        val after = vm.state.value.sections.flatMap { it.scans }.first { it.id == "mug-01" }
        assertFalse(after.canReprocess)
    }
}
