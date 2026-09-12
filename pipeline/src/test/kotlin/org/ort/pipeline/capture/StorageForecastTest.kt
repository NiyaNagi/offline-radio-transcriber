package org.ort.pipeline.capture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.ort.testing.Requirement

/** register R-105: FR-STO-3's early warning had no signal at all before this -- only the hard floor. */
class StorageForecastTest {

    @BeforeEach
    fun reset() {
        StorageForecast.reset()
    }

    private val night = StorageForecast.NIGHT_DURATION_MILLIS

    @Test
    @Requirement("FR-STO-3", "R-105")
    fun `before anything ticks, the forecast reports not yet measured -- never invented`() {
        assertTrue(StorageForecast.state is StorageForecast.State.NotYetMeasured)
    }

    @Test
    @Requirement("FR-STO-3", "R-105")
    fun `FR_STO_3_a_healthy_rate_with_many_nights_left_reports_Fine`() {
        // 1 MB written in one hour -> extrapolated to an 8h night -> 8 MB/night; 800 MB free -> 100 nights.
        StorageForecast.update(
            freeBytes = 800L * 1024 * 1024,
            audioDirectoryBytes = 2L * 1024 * 1024 * 1024,
            bytesWrittenThisSession = 1L * 1024 * 1024,
            sessionElapsedMillis = 3_600_000L,
            floorBytes = 100L * 1024 * 1024,
        )
        val state = StorageForecast.state
        assertTrue(state is StorageForecast.State.Fine)
        assertTrue((state as StorageForecast.State.Fine).nightsLeft > 3.0)
    }

    @Test
    @Requirement("FR-STO-3", "R-105")
    fun `FR_STO_3_forecast_reports_three_nights_left_before_the_floor`() {
        // Free bytes sized so nightsLeft lands just inside the 3-night band: rate is 1 byte/ms,
        // extrapolated over one night gives bytesPerNight == night (millis); pick free bytes ~2.5x that.
        val bytesPerNight = night // 1 byte/ms rate
        StorageForecast.update(
            freeBytes = (bytesPerNight * 2.5).toLong(),
            audioDirectoryBytes = 5_000_000_000L,
            bytesWrittenThisSession = 1_000L,
            sessionElapsedMillis = 1_000L,
            floorBytes = 100L,
        )
        val state = StorageForecast.state
        assertTrue(state is StorageForecast.State.ThreeNightsLeft, "expected ThreeNightsLeft, got $state")
        assertTrue((state as StorageForecast.State.ThreeNightsLeft).nightsLeft in 1.0..3.0)
    }

    @Test
    @Requirement("FR-STO-3", "R-105")
    fun `FR_STO_3_forecast_reports_one_night_left_below_that`() {
        val bytesPerNight = night
        StorageForecast.update(
            freeBytes = (bytesPerNight * 0.5).toLong(),
            audioDirectoryBytes = 5_000_000_000L,
            bytesWrittenThisSession = 1_000L,
            sessionElapsedMillis = 1_000L,
            floorBytes = 100L,
        )
        assertTrue(StorageForecast.state is StorageForecast.State.OneNightLeft)
    }

    @Test
    @Requirement("FR-STO-3", "FR-STO-4", "R-105")
    fun `FR_STO_4_at_or_below_the_floor_is_always_AtFloor_regardless_of_rate`() {
        StorageForecast.update(
            freeBytes = 50L,
            audioDirectoryBytes = 5_000_000_000L,
            bytesWrittenThisSession = 1L, // a trivial rate that would otherwise say "Fine"
            sessionElapsedMillis = 1_000_000L,
            floorBytes = 100L,
        )
        assertTrue(StorageForecast.state is StorageForecast.State.AtFloor)
    }

    @Test
    @Requirement("FR-STO-3", "R-105")
    fun `FR_STO_3_no_elapsed_time_or_nothing_written_yet_reports_not_yet_measured_not_a_guess`() {
        StorageForecast.update(
            freeBytes = 10_000_000_000L,
            audioDirectoryBytes = 0L,
            bytesWrittenThisSession = 0L,
            sessionElapsedMillis = 0L,
            floorBytes = 100L,
        )
        assertTrue(StorageForecast.state is StorageForecast.State.NotYetMeasured)
    }

    @Test
    @Requirement("FR-STO-3", "R-105")
    fun `set is the scenario simulator's direct entry point to an exact state`() {
        val exact = StorageForecast.State.ThreeNightsLeft(freeBytes = 1L, audioDirectoryBytes = 2L, nightsLeft = 2.4)
        StorageForecast.set(exact)
        assertEquals(exact, StorageForecast.state)
    }
}

/** WPARC (FR-STO-3f, being drafted; D39, constitution VI). */
class ArchiveWriteRateForecastTest {

    @BeforeEach
    fun reset() {
        ArchiveWriteRateForecast.reset()
    }

    @Test
    @Requirement("FR-STO-3f", "D39")
    fun `before anything ticks, the rate is not yet measured -- never a fabricated number`() {
        assertTrue(ArchiveWriteRateForecast.state is ArchiveWriteRateForecast.State.NotYetMeasured)
    }

    @Test
    @Requirement("FR-STO-3f", "D39")
    fun `a real write rate is extrapolated to an hourly and monthly figure, labelled measured`() {
        // 60 MB written in one hour -> 60 MB/hour -> 60 * 24 * 30 MB/month.
        ArchiveWriteRateForecast.update(
            bytesWrittenThisSession = 60L * 1_000_000,
            sessionElapsedMillis = 3_600_000L,
        )
        val state = ArchiveWriteRateForecast.state
        assertTrue(state is ArchiveWriteRateForecast.State.Measured)
        state as ArchiveWriteRateForecast.State.Measured
        assertEquals(60_000_000.0, state.bytesPerHour, 0.001)
        assertEquals(60_000_000.0 * 24 * 30, state.bytesPerMonth, 0.001)
    }

    @Test
    @Requirement("FR-STO-3f", "D39")
    fun `no elapsed time or nothing written yet reports not yet measured, not a guess`() {
        ArchiveWriteRateForecast.update(bytesWrittenThisSession = 0L, sessionElapsedMillis = 3_600_000L)
        assertTrue(ArchiveWriteRateForecast.state is ArchiveWriteRateForecast.State.NotYetMeasured)

        ArchiveWriteRateForecast.update(bytesWrittenThisSession = 1_000L, sessionElapsedMillis = 0L)
        assertTrue(ArchiveWriteRateForecast.state is ArchiveWriteRateForecast.State.NotYetMeasured)
    }
}
