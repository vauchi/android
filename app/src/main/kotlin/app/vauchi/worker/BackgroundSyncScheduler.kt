// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import app.vauchi.ui.coreui.CommandDTO
import app.vauchi.ui.coreui.WakeupOutcome
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

/**
 * Background sync as a chain of one-time workers, each armed at the gap Core
 * drew for it (vauchi/private#603 option 2). A periodic job gave every
 * background sync the same 15-min spacing, a fingerprint a relay hop could
 * recognise (KU-9 rerun, 2026-10-16).
 */
object BackgroundSyncScheduler {
    const val WORK_NAME = "vauchi_background_sync"

    /** The fixed-period job earlier builds scheduled; cancelled at start. */
    const val LEGACY_PERIODIC_WORK_NAME = "vauchi_periodic_sync"

    private const val PREFS = "vauchi_background_sync"
    private const val KEY_LAST_DELAY = "last_delay_millis"

    /**
     * Until Core has named a gap: the first run comes at Core's background
     * floor, and a chain that loses its `ScheduleWakeup` re-arms at the
     * background mean rather than stopping.
     */
    private const val FIRST_DELAY_MILLIS = 60_000L
    private const val FALLBACK_DELAY_MILLIS = 900_000L

    /** Core's `ScheduleWakeup` delay, or [lastMillis] when it sent none. */
    fun nextDelayMillis(
        commands: List<CommandDTO>,
        lastMillis: Long,
    ): Long =
        commands
            .filterIsInstance<CommandDTO.ScheduleWakeup>()
            .firstOrNull()
            ?.waitMillis ?: lastMillis

    /** The delay last armed, or the fallback when none was. */
    fun lastDelayMillis(context: Context): Long =
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_DELAY, FALLBACK_DELAY_MILLIS)

    /**
     * Queue the next sync [delayMillis] from now, after the worker that may be
     * running it, so re-arming from inside [SyncWorker] does not cancel itself.
     */
    fun arm(
        context: Context,
        delayMillis: Long,
    ): Operation {
        rememberDelay(context, delayMillis)
        return WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request(delayMillis),
        )
    }

    /**
     * When the app closes: report the standby bucket's allowance and replace
     * the armed sync with the run Core draws within it. A gap drawn in a quiet
     * `rare` spell otherwise holds for up to 24 h, opened app or not (#603 run
     * A2). Without a wakeup in Core's answer the armed sync stays.
     */
    fun rearmOnClose(
        context: Context,
        dispatch: (eventJson: String) -> String,
    ): Operation? {
        val answer = dispatch(StandbyBudget.current(context).eventJson())
        val delayMillis =
            envelopeJson
                .decodeFromString<WakeupOutcome>(answer)
                .commands
                .filterIsInstance<CommandDTO.ScheduleWakeup>()
                .firstOrNull()
                ?.waitMillis ?: return null
        rememberDelay(context, delayMillis)
        return WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request(delayMillis),
        )
    }

    private val envelopeJson = Json { ignoreUnknownKeys = true }

    private fun rememberDelay(
        context: Context,
        delayMillis: Long,
    ) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_DELAY, delayMillis)
            .apply()
    }

    /** At app start: retire the periodic job and arm the chain unless armed. */
    fun start(context: Context): Operation {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(LEGACY_PERIODIC_WORK_NAME)
        return workManager.enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request(FIRST_DELAY_MILLIS),
        )
    }

    private fun request(delayMillis: Long) =
        OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints
                    .Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            ).build()
}
