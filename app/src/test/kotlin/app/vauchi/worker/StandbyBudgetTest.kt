// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.worker

import android.app.usage.UsageStatsManager
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The background allowance each App Standby bucket gives, as the Pixel's
 * JobScheduler reports it (`dumpsys jobscheduler`, 2026-10-19, KU-B1 in
 * #603): a sync takes about a second, so each run is one timing session and
 * the session cap is the limit.
 */
class StandbyBudgetTest {
    @Test
    fun eachBucketReportsItsSessionsPerWindow() {
        assertEquals(StandbyBudget(600, 75), StandbyBudget.forBucket(UsageStatsManager.STANDBY_BUCKET_ACTIVE))
        assertEquals(StandbyBudget(7_200, 10), StandbyBudget.forBucket(UsageStatsManager.STANDBY_BUCKET_WORKING_SET))
        assertEquals(StandbyBudget(28_800, 8), StandbyBudget.forBucket(UsageStatsManager.STANDBY_BUCKET_FREQUENT))
        assertEquals(StandbyBudget(86_400, 3), StandbyBudget.forBucket(UsageStatsManager.STANDBY_BUCKET_RARE))
        assertEquals(StandbyBudget(86_400, 1), StandbyBudget.forBucket(UsageStatsManager.STANDBY_BUCKET_RESTRICTED))
    }

    @Test
    fun anUnknownBucketReportsNoKnownLimit() {
        assertEquals(StandbyBudget(86_400, 0), StandbyBudget.forBucket(99))
    }

    @Test
    fun theEventIsTheWireShapeCoreDecodes() {
        assertEquals(
            """{"BackgroundBudgetObserved":{"window_secs":86400,"runs":3}}""",
            StandbyBudget(86_400, 3).eventJson(),
        )
    }
}
