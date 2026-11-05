package app.facet.scan

import app.facet.geometry.BoxSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScanTuningTest {
    private val tuning = ScanTuning.Default

    @Test
    fun bandsAreContiguousAndOrdered() {
        with(tuning.coverage) {
            assertTrue(0.0 < lowBandMaxDeg && lowBandMaxDeg < midBandMaxDeg && midBandMaxDeg < highBandMaxDeg)
            assertEquals(15.0, sectorSpanDeg)
        }
        assertEquals(CoverageBand.LOW, tuning.bandForElevation(0.0))
        assertEquals(CoverageBand.LOW, tuning.bandForElevation(29.9))
        assertEquals(CoverageBand.MID, tuning.bandForElevation(30.0))
        assertEquals(CoverageBand.HIGH, tuning.bandForElevation(55.0))
        assertEquals(CoverageBand.HIGH, tuning.bandForElevation(80.0))
        assertNull(tuning.bandForElevation(85.0))
        assertNull(tuning.bandForElevation(-5.0))
    }

    @Test
    fun distanceRangeUsesFloorsForSmallBoxes() {
        val mug = BoxSize(118.6, 92.4, 98.0) // D ≈ 179 mm
        val range = tuning.distanceRangeMm(mug)
        assertEquals(200.0, range.start)
        assertEquals(600.0, range.endInclusive)
    }

    @Test
    fun distanceRangeScalesWithLargeBoxes() {
        val chair = BoxSize(600.0, 800.0, 0.0) // D = 1000 mm
        val range = tuning.distanceRangeMm(chair)
        assertEquals(800.0, range.start, 1e-9)
        assertEquals(2_500.0, range.endInclusive, 1e-9)
    }

    @Test
    fun voxelSizesAreClamped() {
        assertEquals(1.0, tuning.quickVoxelMm(BoxSize(20.0, 20.0, 20.0)))      // tiny → floor
        assertEquals(10.0, tuning.quickVoxelMm(BoxSize(2500.0, 2500.0, 2500.0))) // huge → ceiling
        assertEquals(1.0, tuning.highDetailVoxelMm(BoxSize(600.0, 0.0, 0.0)))    // 600/600
        assertEquals(0.4, tuning.highDetailVoxelMm(BoxSize(50.0, 0.0, 0.0)))
    }

    @Test
    fun finishThresholdsAreOrdered() {
        with(tuning.finish) {
            assertTrue(finishEnabledAtCoverage < incompleteAnyBandBelow)
            assertTrue(incompleteAnyBandBelow < incompleteOverallBelow)
        }
    }

    @Test
    fun guidancePrioritiesMatchSpec() {
        assertEquals(1, GuidanceMessage.TRACKING_LOST.priority)
        val sorted = GuidanceMessage.entries.sortedBy { it.priority }
        assertEquals(GuidanceMessage.entries, sorted) // declaration order == priority order
    }
}
