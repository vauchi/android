// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display.exports

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import app.vauchi.contract.Event
import app.vauchi.contract.ExportFileSpec
import app.vauchi.display.AndroidDisplayExecutor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

internal class ExportTestRig : AutoCloseable {
    val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
    val activity = controller.get()
    val authority = "${activity.packageName}.vauchi.display.exports"
    val provider = ExportFileProvider()
    val events = mutableListOf<Event>()
    val spec = ExportFileSpec(listOf(0u.toUByte(), 255u.toUByte(), 3u.toUByte()), "application/octet-stream", "backup.vauchi")
    val directory get() = File(activity.cacheDir, "vauchi-display-exports")
    private val owners = mutableListOf<AndroidDisplayExecutor>()
    private val hosts = mutableListOf<ActivityFileExportHost>()

    init {
        val info = activity.packageManager.resolveContentProvider(authority, PackageManager.GET_META_DATA)!!
        provider.attachInfo(activity, info)
        ShadowContentResolver.registerProviderInternal(authority, provider)
    }

    fun host(
        limits: FileExportLimits = FileExportLimits(),
        clock: () -> Long = System::currentTimeMillis,
        activity: () -> ComponentActivity? = { this.activity },
        io: CoroutineDispatcher = Dispatchers.IO,
        openOutput: (File) -> OutputStream = ::FileOutputStream,
    ): ActivityFileExportHost = ActivityFileExportHost(this.activity, activity, limits, clock, io, openOutput).also(hosts::add)

    fun owner(host: ActivityFileExportHost = host()): AndroidDisplayExecutor =
        AndroidDisplayExecutor(activity, fileExportHost = host) { events.add(it) }.also(owners::add)

    fun takeUri(): Uri {
        val chooser = shadowOf(activity).nextStartedActivity
        return chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!.getParcelableExtra(Intent.EXTRA_STREAM)!!
    }

    fun read(uri: Uri): ByteArray = activity.contentResolver.openInputStream(uri)!!.use { it.readBytes() }

    override fun close() {
        owners.forEach(AndroidDisplayExecutor::close)
        hosts.forEach(ActivityFileExportHost::close)
        shadowOf(Looper.getMainLooper()).idle()
        if (!activity.isDestroyed) controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
    }
}
