// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.worker

import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build

/**
 * How many background runs Android allows this app per window, by App
 * Standby bucket. Reported to Core, which draws the background schedule
 * within it (vauchi/private#603 option 1); Core decides the gaps.
 *
 * A sync takes about a second, so each run is one JobScheduler timing
 * session and the per-bucket session cap is the limit. Values read on the
 * Pixel with `dumpsys jobscheduler` (2026-10-19): `qc_window_size_*_ms` and
 * `qc_max_session_count_*`.
 */
data class StandbyBudget(
    val windowSecs: Int,
    val runs: Int,
) {
    fun eventJson(): String = """{"BackgroundBudgetObserved":{"window_secs":$windowSecs,"runs":$runs}}"""

    companion object {
        private const val DAY_SECS = 86_400

        fun forBucket(bucket: Int): StandbyBudget =
            when (bucket) {
                UsageStatsManager.STANDBY_BUCKET_ACTIVE -> StandbyBudget(600, 75)
                UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> StandbyBudget(7_200, 10)
                UsageStatsManager.STANDBY_BUCKET_FREQUENT -> StandbyBudget(28_800, 8)
                UsageStatsManager.STANDBY_BUCKET_RARE -> StandbyBudget(DAY_SECS, 3)
                UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> StandbyBudget(DAY_SECS, 1)
                else -> StandbyBudget(DAY_SECS, 0)
            }

        /** This app's current allowance; no known limit before Android 9. */
        fun current(context: Context): StandbyBudget {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return StandbyBudget(DAY_SECS, 0)
            val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            return forBucket(usage?.appStandbyBucket ?: -1)
        }
    }
}
