// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.proximity

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import uniffi.vauchi_platform.MobileEvent
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-shot device location capture for the exchange "where we met" annotation
 * (ADR-051 capture-at-exchange).
 *
 * Wraps the platform [LocationManager]: checks the runtime location permission,
 * asks for a single fix within the timeout, and reports the outcome exactly once
 * as the matching [MobileEvent] — [MobileEvent.LocationResult],
 * [MobileEvent.PermissionDenied] (transport `"location"`), or
 * [MobileEvent.HardwareUnavailable] (transport `"location"`). Core
 * (`AppEngine`) consumes the event and records `set_exchange_location`.
 *
 * The Activity owns the runtime *permission request* (it needs an
 * ActivityResultLauncher); this service only checks the granted state and does
 * the CoreLocation-equivalent plumbing — the same Activity/Context split as
 * [AudioProximityService] (ADR-030/031). CC-23: the engine is driven by the
 * resulting event, never by polling the OS permission modal.
 */
class LocationCaptureService private constructor(
    private val appContext: Context,
) {
    companion object {
        @Volatile
        private var instance: LocationCaptureService? = null

        fun getInstance(context: Context): LocationCaptureService =
            instance ?: synchronized(this) {
                instance ?: LocationCaptureService(context.applicationContext)
                    .also { instance = it }
            }

        private const val TRANSPORT = "location"

        /** No location permission at all → the generic denied reply; null = proceed. */
        internal fun permissionDeniedEvent(
            fine: Boolean,
            coarse: Boolean,
        ): MobileEvent? = if (!fine && !coarse) MobileEvent.PermissionDenied(TRANSPORT) else null

        /** A fix without reported accuracy maps to a null accuracy, not a bogus 0. */
        internal fun locationResultEvent(
            latitude: Double,
            longitude: Double,
            hasAccuracy: Boolean,
            accuracy: Float,
        ): MobileEvent =
            MobileEvent.LocationResult(
                latitude = latitude,
                longitude = longitude,
                accuracyMeters = if (hasAccuracy) accuracy else null,
            )
    }

    /**
     * Request a single location fix. [onResult] is invoked exactly once, on the
     * main thread, with the resulting [MobileEvent]. A missing fix within
     * [timeoutMs] reports [MobileEvent.HardwareUnavailable] so core clears its
     * pending capture rather than hanging.
     */
    @SuppressLint("MissingPermission") // guarded by the checkSelfPermission gate below
    fun requestOneShot(
        timeoutMs: Long,
        onResult: (MobileEvent) -> Unit,
    ) {
        val fine = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        val denied = permissionDeniedEvent(fine, coarse)
        if (denied != null) {
            onResult(denied)
            return
        }

        val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val provider = manager?.let { pickProvider(it, fine) }
        if (manager == null || provider == null) {
            onResult(MobileEvent.HardwareUnavailable(TRANSPORT))
            return
        }

        val delivered = AtomicBoolean(false)
        val main = Handler(Looper.getMainLooper())

        fun deliver(event: MobileEvent) {
            if (delivered.compareAndSet(false, true)) {
                main.post { onResult(event) }
            }
        }

        val timeout = Runnable { deliver(MobileEvent.HardwareUnavailable(TRANSPORT)) }
        main.postDelayed(timeout, timeoutMs)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            manager.getCurrentLocation(provider, null, appContext.mainExecutor) { loc ->
                main.removeCallbacks(timeout)
                deliver(loc?.let(::locationResult) ?: MobileEvent.HardwareUnavailable(TRANSPORT))
            }
        } else {
            val listener =
                object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        main.removeCallbacks(timeout)
                        manager.removeUpdates(this)
                        deliver(locationResult(location))
                    }

                    override fun onProviderDisabled(provider: String) {}

                    override fun onProviderEnabled(provider: String) {}

                    @Deprecated("Required by the pre-API-30 LocationListener contract")
                    override fun onStatusChanged(
                        provider: String?,
                        status: Int,
                        extras: Bundle?,
                    ) {
                    }
                }
            manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) ==
            PackageManager.PERMISSION_GRANTED

    /** Prefer GPS when fine-location is granted; otherwise fall back to network. */
    private fun pickProvider(
        manager: LocationManager,
        fine: Boolean,
    ): String? =
        when {
            fine && manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> {
                LocationManager.GPS_PROVIDER
            }

            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> {
                LocationManager.NETWORK_PROVIDER
            }

            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> {
                LocationManager.GPS_PROVIDER
            }

            else -> {
                null
            }
        }

    private fun locationResult(location: Location): MobileEvent =
        locationResultEvent(
            latitude = location.latitude,
            longitude = location.longitude,
            hasAccuracy = location.hasAccuracy(),
            accuracy = location.accuracy,
        )
}
