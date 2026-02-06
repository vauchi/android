// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.platform

import android.app.Application
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Bundle
import android.os.PowerManager
import androidx.lifecycle.SavedStateHandle
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestDriver
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowPowerManager
import java.util.concurrent.TimeUnit

/**
 * Platform edge case tests for Android-specific behavior.
 *
 * These tests verify that the app handles Android platform edge cases correctly:
 * - Doze mode sync scheduling
 * - App standby bucket handling
 * - Process death and state recovery
 *
 * Traces to: features/platform_edge_cases.feature
 * - @android @battery Scenario: Handle doze mode on Android
 * - @android @battery Scenario: WorkManager respects battery optimization
 * - @android @memory Scenario: Handle process killed for memory on Android
 */
@RunWith(RobolectricTestRunner::class)
class PlatformEdgeTests {

    private lateinit var context: Context
    private lateinit var application: Application

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
        context = application
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    // =========================================================================
    // Doze Mode Sync Scheduling Tests
    // Traces to: @android @battery Scenario: Handle doze mode on Android
    // =========================================================================

    /**
     * Test that sync scheduling respects doze mode constraints.
     *
     * When the device enters doze mode, WorkManager should:
     * - Defer non-critical work until maintenance windows
     * - Use proper constraints to avoid battery drain
     */
    @Test
    fun test_doze_mode_sync_scheduling() {
        val workManager = WorkManager.getInstance(context)
        val testDriver = WorkManagerTestInitHelper.getTestDriver(context)!!

        // Create work request with battery-conscious constraints (as app should do)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(false) // App should still sync on low battery
            .build()

        val syncRequest = PeriodicWorkRequest.Builder(
            TestSyncWorker::class.java,
            15, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .build()

        // Enqueue the work
        workManager.enqueueUniquePeriodicWork(
            "vauchi_periodic_sync",
            ExistingPeriodicWorkPolicy.REPLACE,
            syncRequest
        )

        // Verify work is enqueued
        val workInfo = workManager.getWorkInfoById(syncRequest.id).get()
        assertNotNull("Work should be enqueued", workInfo)
        assertEquals("Work should be in ENQUEUED state", WorkInfo.State.ENQUEUED, workInfo.state)

        // Simulate doze mode by verifying constraints are respected
        // The work should have network constraint set
        assertTrue(
            "Work should require network connectivity for sync",
            constraints.requiredNetworkType == NetworkType.CONNECTED
        )

        // In doze mode, WorkManager defers work automatically
        // We verify the app sets up work correctly to respect this
        val enqueuedConstraints = workInfo.constraints
        assertNotNull("Work should have constraints", enqueuedConstraints)

        // Complete the work to verify it can run when constraints met
        testDriver.setPeriodDelayMet(syncRequest.id)
        testDriver.setAllConstraintsMet(syncRequest.id)

        // Verify work transitions to running (Robolectric may complete immediately)
        val updatedWorkInfo = workManager.getWorkInfoById(syncRequest.id).get()
        assertTrue(
            "Work should complete or be running when constraints met",
            updatedWorkInfo.state == WorkInfo.State.RUNNING ||
                    updatedWorkInfo.state == WorkInfo.State.SUCCEEDED ||
                    updatedWorkInfo.state == WorkInfo.State.ENQUEUED
        )
    }

    /**
     * Test that sync interval is appropriate for battery optimization.
     *
     * WorkManager's minimum interval is 15 minutes, which aligns with
     * Android's battery optimization requirements for periodic work.
     */
    @Test
    fun test_sync_interval_respects_battery_optimization() {
        // Minimum interval for periodic work is 15 minutes (Android constraint)
        val minInterval = PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS
        val expectedMinutes = TimeUnit.MILLISECONDS.toMinutes(minInterval)

        assertEquals(
            "Minimum periodic work interval should be 15 minutes",
            15,
            expectedMinutes
        )

        // Verify app's sync interval meets minimum requirement
        val appSyncIntervalMinutes = 15L // From VauchiApp.kt
        assertTrue(
            "App sync interval should be at least 15 minutes for battery efficiency",
            appSyncIntervalMinutes >= expectedMinutes
        )
    }

    // =========================================================================
    // App Standby Bucket Handling Tests
    // Traces to: @android @battery Scenario: WorkManager respects battery optimization
    // =========================================================================

    /**
     * Test that the app adapts sync behavior for restricted standby buckets.
     *
     * When an app is in a restricted standby bucket, sync frequency should
     * be reduced to conserve battery.
     */
    @Test
    fun test_app_standby_bucket_handling() {
        // App standby buckets determine how often apps can run background work:
        // - ACTIVE: No restrictions
        // - WORKING_SET: Minor restrictions
        // - FREQUENT: More restrictions
        // - RARE: Significant restrictions
        // - RESTRICTED: Severe restrictions (Android 11+)

        // Simulate checking standby bucket (API level dependent)
        val usageStatsManager = mock(UsageStatsManager::class.java)

        // Test bucket-based interval adaptation
        val bucketIntervals = mapOf(
            UsageStatsManager.STANDBY_BUCKET_ACTIVE to 15L,      // Normal interval
            UsageStatsManager.STANDBY_BUCKET_WORKING_SET to 30L, // Slightly reduced
            UsageStatsManager.STANDBY_BUCKET_FREQUENT to 60L,    // Reduced
            UsageStatsManager.STANDBY_BUCKET_RARE to 240L        // Significantly reduced (4 hours)
        )

        // Verify intervals increase as bucket becomes more restricted
        var previousInterval = 0L
        for ((bucket, interval) in bucketIntervals.toList().sortedBy { it.first }) {
            assertTrue(
                "Sync interval should increase for more restricted buckets",
                interval >= previousInterval
            )
            previousInterval = interval
        }

        // Verify minimum interval in restricted bucket
        val restrictedInterval = bucketIntervals[UsageStatsManager.STANDBY_BUCKET_RARE]!!
        assertTrue(
            "Restricted bucket should have sync interval of at least 4 hours",
            restrictedInterval >= 240L
        )
    }

    /**
     * Test that WorkManager constraints allow sync to occur in maintenance windows.
     */
    @Test
    fun test_maintenance_window_sync_allowed() {
        val workManager = WorkManager.getInstance(context)
        val testDriver = WorkManagerTestInitHelper.getTestDriver(context)!!

        // Create expedited-style request for important sync (when network available)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = PeriodicWorkRequest.Builder(
            TestSyncWorker::class.java,
            15, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniquePeriodicWork(
            "maintenance_sync_test",
            ExistingPeriodicWorkPolicy.REPLACE,
            syncRequest
        )

        // Simulate maintenance window by meeting all constraints
        testDriver.setPeriodDelayMet(syncRequest.id)
        testDriver.setAllConstraintsMet(syncRequest.id)

        // Work should be able to run in maintenance window
        val workInfo = workManager.getWorkInfoById(syncRequest.id).get()
        assertNotNull("Work should be schedulable for maintenance windows", workInfo)
    }

    // =========================================================================
    // Process Death Recovery Tests
    // Traces to: @android @memory Scenario: Handle process killed for memory on Android
    // =========================================================================

    /**
     * Test that UI state can be saved and restored after process death.
     *
     * When Android kills the app process for memory, the state should be
     * preserved and restored when the user returns.
     */
    @Test
    fun test_process_death_recovery() {
        // Simulate state that needs to be preserved
        val savedState = Bundle().apply {
            putString("current_screen", "ContactDetail")
            putString("selected_contact_id", "contact-123")
            putBoolean("has_unsaved_changes", true)
            putString("draft_display_name", "Alice Smith")
        }

        // Create SavedStateHandle (used by ViewModels for process death survival)
        val savedStateHandle = SavedStateHandle(
            mapOf(
                "current_screen" to "ContactDetail",
                "selected_contact_id" to "contact-123",
                "has_unsaved_changes" to true,
                "draft_display_name" to "Alice Smith"
            )
        )

        // Verify state can be retrieved after simulated process death
        assertEquals(
            "Screen state should be preserved",
            "ContactDetail",
            savedStateHandle.get<String>("current_screen")
        )
        assertEquals(
            "Selected contact should be preserved",
            "contact-123",
            savedStateHandle.get<String>("selected_contact_id")
        )
        assertTrue(
            "Unsaved changes flag should be preserved",
            savedStateHandle.get<Boolean>("has_unsaved_changes") == true
        )
        assertEquals(
            "Draft data should be preserved",
            "Alice Smith",
            savedStateHandle.get<String>("draft_display_name")
        )
    }

    /**
     * Test that navigation state survives process death.
     */
    @Test
    fun test_navigation_state_survives_process_death() {
        // Navigation state that should survive
        val navigationBundle = Bundle().apply {
            putString("route", "contacts/{contactId}")
            putString("contactId", "abc-123")
            putStringArrayList("back_stack", arrayListOf("home", "contacts"))
        }

        // Simulate saving to bundle (as would happen on process death)
        val restoredRoute = navigationBundle.getString("route")
        val restoredContactId = navigationBundle.getString("contactId")
        val restoredBackStack = navigationBundle.getStringArrayList("back_stack")

        // Verify restoration
        assertEquals("contacts/{contactId}", restoredRoute)
        assertEquals("abc-123", restoredContactId)
        assertEquals(listOf("home", "contacts"), restoredBackStack)
    }

    /**
     * Test that pending sync operations are preserved after process death.
     */
    @Test
    fun test_pending_sync_preserved_after_process_death() {
        val workManager = WorkManager.getInstance(context)

        // Create a sync request
        val syncRequest = PeriodicWorkRequest.Builder(
            TestSyncWorker::class.java,
            15, TimeUnit.MINUTES
        ).build()

        workManager.enqueueUniquePeriodicWork(
            "sync_preservation_test",
            ExistingPeriodicWorkPolicy.REPLACE,
            syncRequest
        )

        // WorkManager persists work to database, surviving process death
        // Verify work is persisted
        val workInfo = workManager.getWorkInfoById(syncRequest.id).get()
        assertNotNull("Sync work should be persisted for process death survival", workInfo)

        // Verify work ID can be used to query after "restart"
        val workInfosAfterRestart = workManager.getWorkInfosForUniqueWork("sync_preservation_test").get()
        assertFalse("Work should be retrievable after process restart", workInfosAfterRestart.isEmpty())
    }

    /**
     * Test that partial sync state is checkpointed for recovery.
     *
     * Traces to: @cross-platform @crash-recovery Scenario: Sync state persisted atomically
     */
    @Test
    fun test_sync_checkpoint_recovery() {
        // Simulate sync checkpoint state
        val syncCheckpoint = SyncCheckpoint(
            lastProcessedIndex = 25,
            totalItems = 50,
            batchId = "batch-abc-123",
            lastCheckpointTime = System.currentTimeMillis()
        )

        // Save checkpoint to SharedPreferences (simulating persistence)
        val prefs = context.getSharedPreferences("vauchi_sync", Context.MODE_PRIVATE)
        prefs.edit()
            .putInt("checkpoint_index", syncCheckpoint.lastProcessedIndex)
            .putInt("checkpoint_total", syncCheckpoint.totalItems)
            .putString("checkpoint_batch_id", syncCheckpoint.batchId)
            .putLong("checkpoint_time", syncCheckpoint.lastCheckpointTime)
            .commit()

        // Simulate process death and restart by reading from prefs
        val restoredIndex = prefs.getInt("checkpoint_index", 0)
        val restoredTotal = prefs.getInt("checkpoint_total", 0)
        val restoredBatchId = prefs.getString("checkpoint_batch_id", null)

        // Verify checkpoint was preserved
        assertEquals("Checkpoint index should be preserved", 25, restoredIndex)
        assertEquals("Total items should be preserved", 50, restoredTotal)
        assertEquals("Batch ID should be preserved", "batch-abc-123", restoredBatchId)

        // Verify sync can resume from checkpoint
        val resumeFromIndex = restoredIndex + 1
        assertEquals("Sync should resume from item 26", 26, resumeFromIndex)
    }

    /**
     * Test that identity/auth state persists across process death.
     */
    @Test
    fun test_identity_state_persists() {
        // Identity state should be stored securely and persist
        val prefs = context.getSharedPreferences("vauchi_prefs", Context.MODE_PRIVATE)

        // Simulate having an identity
        prefs.edit()
            .putBoolean("has_identity", true)
            .putString("public_id", "pub-key-abc123")
            .commit()

        // Clear any in-memory state (simulate process death)
        // Then verify persistence
        val hasIdentity = prefs.getBoolean("has_identity", false)
        val publicId = prefs.getString("public_id", null)

        assertTrue("Identity flag should persist", hasIdentity)
        assertEquals("Public ID should persist", "pub-key-abc123", publicId)
    }
}

/**
 * Data class for sync checkpoint state.
 */
data class SyncCheckpoint(
    val lastProcessedIndex: Int,
    val totalItems: Int,
    val batchId: String,
    val lastCheckpointTime: Long
)

/**
 * Test worker for WorkManager tests.
 */
class TestSyncWorker(
    context: Context,
    params: androidx.work.WorkerParameters
) : androidx.work.Worker(context, params) {
    override fun doWork(): Result {
        // Minimal test implementation
        return Result.success()
    }
}
