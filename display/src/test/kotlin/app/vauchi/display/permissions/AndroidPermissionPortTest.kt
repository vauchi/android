// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display.permissions

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import app.vauchi.contract.Command
import app.vauchi.contract.Event
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// Feature: contact_exchange.feature @exchange
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 31, 35])
class AndroidPermissionPortTest {
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val events = mutableListOf<Event>()
    private val requested = mutableListOf<List<String>>()
    private var finished: (() -> Unit)? = null
    private var releases = 0
    private var failLaunch = false
    private var answerInline = false
    private val host =
        PermissionRequestHost { permissions, reply, _ ->
            requested.add(permissions.toList())
            if (failLaunch) throw IllegalStateException("private native detail")
            finished = reply
            if (answerInline) reply()
            AutoCloseable { releases++ }
        }
    private val port = AndroidPermissionPort(application, host, events::add)

    @Before
    fun denyRuntimePermissions() {
        shadowOf(application).denyPermissions(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        shadowOf(application.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).setNotificationsEnabled(true)
    }

    @After
    fun release() = port.close()

    private fun request(transport: String) = port.execute(Command.RequestPermission(transport))

    @Test
    fun cameraResultIsRecheckedAgainstTheOsAndDeliveredOnce() {
        assertTrue(request("camera"))
        assertEquals(listOf(listOf(Manifest.permission.CAMERA)), requested)
        assertEquals(emptyList<Event>(), events)
        shadowOf(application).grantPermissions(Manifest.permission.CAMERA)
        finished!!()
        finished!!()
        assertEquals(listOf(Event.PermissionGranted("camera")), events)
        assertEquals(1, releases)
    }

    @Test
    fun microphoneDenialUsesTheCanonicalTransportWithoutInventingCopy() {
        assertTrue(request("microphone"))
        assertEquals(listOf(listOf(Manifest.permission.RECORD_AUDIO)), requested)
        finished!!()
        assertEquals(listOf(Event.PermissionDenied("microphone")), events)
        assertEquals(1, releases)
    }

    @Test
    fun bluetoothUsesTheInstalledAndroidVersionAndRequiresItsWholeNativeGrant() {
        assertTrue(request("ble"))
        val expected =
            if (Build.VERSION.SDK_INT >= 31) {
                listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
            } else {
                listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        assertEquals(listOf(expected), requested)
        shadowOf(application).grantPermissions(*expected.dropLast(1).toTypedArray())
        finished!!()
        assertEquals(listOf(Event.PermissionDenied("ble")), events)
    }

    @Test
    fun approximateLocationIsANativeGrantRatherThanAnAutomaticPrecisionEscalation() {
        assertTrue(request("location"))
        assertEquals(listOf(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)), requested)
        shadowOf(application).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        finished!!()
        assertEquals(listOf(Event.PermissionGranted("location")), events)
    }

    @Test
    fun alreadyGrantedPermissionDoesNotAcquireAnotherPromptHost() {
        shadowOf(application).grantPermissions(Manifest.permission.CAMERA)
        assertTrue(request("camera"))
        assertEquals(emptyList<List<String>>(), requested)
        assertEquals(listOf(Event.PermissionGranted("camera")), events)
        assertEquals(0, releases)
    }

    @Test
    fun arbitraryStringsCannotBecomeAndroidPermissionNames() {
        for (transport in listOf("", "android.permission.READ_CONTACTS", "camera\u0000", "CAMERA", "nfc")) {
            assertTrue(request(transport))
        }
        assertEquals(emptyList<List<String>>(), requested)
        assertEquals(
            listOf("", "android.permission.READ_CONTACTS", "camera\u0000", "CAMERA", "nfc").map(Event::HardwareUnavailable),
            events,
        )
    }

    @Test
    fun aSecondPromptIsRefusedWithoutQueuingOrReplacingTheFirst() {
        assertTrue(request("camera"))
        assertTrue(request("microphone"))
        assertEquals(listOf(listOf(Manifest.permission.CAMERA)), requested)
        assertEquals(listOf(Event.HardwareError("permission_request_busy", "microphone")), events)
        shadowOf(application).grantPermissions(Manifest.permission.CAMERA)
        finished!!()
        assertEquals(Event.PermissionGranted("camera"), events.last())
        assertEquals(1, releases)
    }

    @Test
    fun closingFencesTheNativeAnswerAndReleasesItsCallbackLease() {
        assertTrue(request("camera"))
        port.close()
        port.close()
        shadowOf(application).grantPermissions(Manifest.permission.CAMERA)
        finished!!()
        assertEquals(1, releases)
        assertEquals(emptyList<Event>(), events)
        assertFalse(request("microphone"))
        assertEquals(listOf(listOf(Manifest.permission.CAMERA)), requested)
    }

    @Test
    fun missingNativeHostReportsUnavailableOnlyWhenAccessIsNotAlreadyGranted() {
        AndroidPermissionPort(application, null, events::add).use { unhosted ->
            assertTrue(unhosted.execute(Command.RequestPermission("camera")))
            shadowOf(application).grantPermissions(Manifest.permission.CAMERA)
            assertTrue(unhosted.execute(Command.RequestPermission("camera")))
        }
        assertEquals(listOf(Event.HardwareUnavailable("camera"), Event.PermissionGranted("camera")), events)
        assertEquals(emptyList<List<String>>(), requested)
    }

    @Test
    fun nativeLaunchFailureIsSanitizedAndDoesNotKeepThePromptSlot() {
        failLaunch = true
        assertTrue(request("camera"))
        assertEquals(listOf(Event.HardwareError("permission_request_failed", "camera")), events)
        failLaunch = false
        assertTrue(request("microphone"))
        assertEquals(listOf(Manifest.permission.RECORD_AUDIO), requested.last())
        finished!!()
        assertEquals(Event.PermissionDenied("microphone"), events.last())
    }

    @Test
    fun anInlineNativeAnswerStillReleasesTheReturnedLeaseExactlyOnce() {
        answerInline = true
        assertTrue(request("camera"))
        assertEquals(listOf(Event.PermissionDenied("camera")), events)
        assertEquals(1, releases)
        assertTrue(request("microphone"))
        assertEquals(Event.PermissionDenied("microphone"), events.last())
        assertEquals(2, releases)
    }

    @Test
    fun nativeHostLossReportsUnavailableAndDoesNotStrandThePromptSlot() {
        var gone: (() -> Unit)? = null
        val disappearing =
            PermissionRequestHost { _, _, onUnavailable ->
                gone = onUnavailable
                AutoCloseable { releases++ }
            }
        AndroidPermissionPort(application, disappearing, events::add).use { hosted ->
            assertTrue(hosted.execute(Command.RequestPermission("camera")))
            gone!!()
            gone!!()
            assertEquals(listOf(Event.HardwareUnavailable("camera")), events)
            assertEquals(1, releases)
            assertTrue(hosted.execute(Command.RequestPermission("microphone")))
        }
        assertEquals(2, releases)
    }

    @Test
    fun permissionQueryFailureOnAnAsyncAnswerIsSanitizedAndEndsTheRequest() {
        var fail = false
        val context =
            object : ContextWrapper(application) {
                override fun getApplicationContext(): Context = this

                override fun checkPermission(
                    permission: String,
                    pid: Int,
                    uid: Int,
                ): Int {
                    if (fail) throw IllegalStateException("private native detail")
                    return super.checkPermission(permission, pid, uid)
                }
            }
        AndroidPermissionPort(context, host, events::add).use { hosted ->
            assertTrue(hosted.execute(Command.RequestPermission("camera")))
            fail = true
            finished!!()
            assertEquals(listOf(Event.HardwareError("permission_request_failed", "camera")), events)
            assertEquals(1, releases)
        }
    }

    @Test
    fun notificationPromptsFollowRuntimeSupportAndRetainDisabledAppSettings() {
        assertTrue(request("notifications"))
        if (Build.VERSION.SDK_INT >= 33) {
            assertEquals(listOf(listOf(Manifest.permission.POST_NOTIFICATIONS)), requested)
            shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            finished!!()
            assertEquals(1, releases)
        } else {
            assertEquals(emptyList<List<String>>(), requested)
        }
        assertEquals(listOf(Event.PermissionGranted("notifications")), events)
        shadowOf(application.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).setNotificationsEnabled(false)
        assertTrue(request("notifications"))
        assertEquals(Event.PermissionDenied("notifications"), events.last())
    }
}
