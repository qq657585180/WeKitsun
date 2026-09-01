package dev.ujhhgtg.wekit.features.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class FeatureRuntimeReporterTest {

    @BeforeEach
    fun clear() {
        FeatureRuntimeReporter.clear()
    }

    @AfterEach
    fun tearDown() {
        FeatureRuntimeReporter.clear()
    }

    @Test
    fun okReportIsRecorded() {
        FeatureRuntimeReporter.report("feature-a", ok = true, detail = "added")

        val records = FeatureRuntimeReporter.snapshot()
        assertEquals(1, records.size)
        assertEquals("feature-a", records[0].technicalId)
        assertEquals(FeatureRuntimeReporter.Status.OK, records[0].status)
        assertEquals("added", records[0].detail)
        assertFalse(FeatureRuntimeReporter.hasIssues())
    }

    @Test
    fun partialReportIsRecordedAndCountedAsIssue() {
        FeatureRuntimeReporter.report("feature-b", ok = false, detail = "root missing")

        val records = FeatureRuntimeReporter.snapshot()
        assertEquals(1, records.size)
        assertEquals(FeatureRuntimeReporter.Status.PARTIAL, records[0].status)
        assertTrue(FeatureRuntimeReporter.hasIssues())
        assertEquals(1, FeatureRuntimeReporter.issueCount())
    }

    @Test
    fun laterReportOverridesEarlierOne() {
        FeatureRuntimeReporter.report("feature-c", ok = false, detail = "first failure")
        FeatureRuntimeReporter.report("feature-c", ok = true, detail = "recovered")

        val records = FeatureRuntimeReporter.snapshot()
        assertEquals(1, records.size)
        assertEquals(FeatureRuntimeReporter.Status.OK, records[0].status)
        assertEquals("recovered", records[0].detail)
    }

    @Test
    fun snapshotIsSortedByTechnicalIdAndEmptyAfterClear() {
        FeatureRuntimeReporter.report("zebra", ok = true)
        FeatureRuntimeReporter.report("alpha", ok = false, detail = "boom")

        val sorted = FeatureRuntimeReporter.snapshot()
        assertEquals(listOf("alpha", "zebra"), sorted.map { it.technicalId })

        FeatureRuntimeReporter.clear()
        assertTrue(FeatureRuntimeReporter.snapshot().isEmpty())
        assertEquals(0, FeatureRuntimeReporter.issueCount())
    }
}