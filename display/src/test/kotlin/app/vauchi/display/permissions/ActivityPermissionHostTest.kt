// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display.permissions

import android.Manifest
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivity

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 31, 35])
class ActivityPermissionHostTest {
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
    private val activity = controller.get()
    private val native = shadowOf(activity)
    private val host = ActivityPermissionHost { activity }
    private val main = shadowOf(Looper.getMainLooper())
    private var finished = 0
    private var unavailable = 0
    private val leases = mutableListOf<AutoCloseable>()

    private fun request(): AutoCloseable =
        host
            .request(
                listOf(Manifest.permission.CAMERA),
                { finished++ },
                { unavailable++ },
            ).also(leases::add)

    private fun answer(request: ShadowActivity.PermissionsRequest) {
        activity.answerPermissions(request)
    }

    @After
    fun release() {
        leases.forEach(AutoCloseable::close)
        if (activity.lifecycle.currentState == Lifecycle.State.RESUMED) controller.pause()
        if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.stop()
        if (!activity.isDestroyed) controller.destroy()
        main.idle()
    }

    @Test
    fun aNativeRequestReturnsExactlyOneCompletionAndDetachesItsLease() {
        request()
        val pending = native.lastRequestedPermission
        assertEquals(listOf(Manifest.permission.CAMERA), pending.requestedPermissions.toList())
        answer(pending)
        answer(pending)
        main.idle()
        assertEquals(1, finished)
        assertEquals(0, unavailable)
        request()
        assertTrue(native.lastRequestedPermission.requestCode != pending.requestCode)
    }

    @Test
    fun canceledNativeAnswersCannotCompleteAReplacementRequest() {
        val canceled = request()
        val stale = native.lastRequestedPermission
        canceled.close()
        request()
        assertEquals(stale.requestCode, native.lastRequestedPermission.requestCode)
        assertEquals(0, finished)
        assertEquals(1, unavailable)
        answer(stale)
        main.idle()
        assertEquals(0, finished)
        assertEquals(1, unavailable)
        request()
        val current = native.lastRequestedPermission
        assertTrue(stale.requestCode != current.requestCode)
        assertEquals(0, finished)
        answer(current)
        assertEquals(1, finished)
        assertEquals(1, unavailable)
    }

    @Test
    fun aSecondRequestCannotReplaceTheActiveActivityCallback() {
        request()
        val pending = native.lastRequestedPermission
        request()
        assertEquals(pending.requestCode, native.lastRequestedPermission.requestCode)
        assertEquals(0, finished)
        assertEquals(1, unavailable)
        answer(pending)
        assertEquals(1, finished)
        assertEquals(1, unavailable)
    }

    @Test
    fun destroyingAnActivityReportsUnavailableAndFencesItsLaterResult() {
        val lease = request()
        val pending = native.lastRequestedPermission
        controller.pause().stop().destroy()
        lease.close()
        answer(pending)
        assertEquals(0, finished)
        assertEquals(1, unavailable)
    }

    @Test
    fun aStoppedActivityDoesNotLaunchPermissionUi() {
        controller.pause().stop()
        request()
        assertEquals(null, native.lastRequestedPermission)
        assertEquals(0, finished)
        assertEquals(1, unavailable)
    }

    @Test
    fun aMissingActivityProviderReportsUnavailableWithoutRetainingARequest() {
        ActivityPermissionHost { null }.request(listOf(Manifest.permission.CAMERA), { finished++ }, { unavailable++ }).close()
        assertEquals(null, native.lastRequestedPermission)
        assertEquals(0, finished)
        assertEquals(1, unavailable)
    }

    @Test
    fun cancelingALeasePreventsDestructionFromPublishingAnotherOutcome() {
        val lease = request()
        lease.close()
        lease.close()
        controller.pause().stop().destroy()
        assertEquals(0, finished)
        assertEquals(0, unavailable)
    }

    @Test
    fun completedNativeRequestsReleaseTheirActivityRegistryRoute() {
        request()
        val pending = native.lastRequestedPermission
        answer(pending)
        main.idle()
        assertFalse(activity.activityResultRegistry.dispatchResult(pending.requestCode, emptyMap<String, Boolean>()))
        assertEquals(1, finished)
        assertEquals(0, unavailable)
    }

    @Test
    fun nativeLaunchRequiresTheAndroidMainOwner() {
        var failure: Throwable? = null
        val worker =
            Thread {
                try {
                    request()
                } catch (error: Throwable) {
                    failure = error
                }
            }
        worker.start()
        worker.join(2000)
        assertFalse(worker.isAlive)
        assertEquals(IllegalStateException::class.java, failure?.javaClass)
        assertEquals(null, native.lastRequestedPermission)
        assertEquals(0, finished)
        assertEquals(0, unavailable)
    }
}
