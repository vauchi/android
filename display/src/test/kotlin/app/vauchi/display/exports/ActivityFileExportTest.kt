// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
// SPDX-License-Identifier: GPL-3.0-or-later

package app.vauchi.display.exports

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Looper
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import app.vauchi.contract.Command
import app.vauchi.contract.Event
import app.vauchi.contract.ExportFileSpec
import app.vauchi.display.AndroidDisplayExecutor
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference

// Feature: generic_presentation_protocol.feature — native prepared file sharing.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35])
class ActivityFileExportTest {
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
    private val activity = controller.get()
    private val authority = "${activity.packageName}.vauchi.display.exports"
    private val provider = ExportFileProvider()
    private val owners = mutableListOf<AndroidDisplayExecutor>()
    private val events = mutableListOf<Event>()
    private val bytes = byteArrayOf(0, -1, 13, 10, 42)
    private val spec = ExportFileSpec(bytes.map { it.toUByte() }, "application/vnd.vauchi.test+json", "../../秘密.backup")

    @Before
    fun attachProvider() {
        val info = activity.packageManager.resolveContentProvider(authority, PackageManager.GET_META_DATA)!!
        provider.attachInfo(activity, info)
        ShadowContentResolver.registerProviderInternal(authority, provider)
    }

    @After
    fun release() {
        owners.forEach(AndroidDisplayExecutor::close)
        shadowOf(Looper.getMainLooper()).idle()
        controller.pause().stop().destroy()
    }

    private fun owner(host: ActivityFileExportHost = ActivityFileExportHost(activity, { activity })): AndroidDisplayExecutor =
        AndroidDisplayExecutor(activity, fileExportHost = host) { events.add(it) }.also(owners::add)

    private fun sharedUri(): Uri {
        val chooser = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION, chooser.flags)
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals(spec.mimeType, send.type)
        val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals(uri, send.clipData!!.getItemAt(0).uri)
        assertEquals(1, send.clipData!!.itemCount)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK, send.flags)
        assertNull(send.component)
        assertNull(send.data)
        assertNull(send.getStringExtra(Intent.EXTRA_TEXT))
        return uri
    }

    @Test
    fun exactPreparedFileIsSharedReadOnlyAndSurvivesOwnerClose() {
        val owner = owner()
        runBlocking { assertTrue(owner.executeAsync(Command.ExportFile(spec))) }
        val uri = sharedUri()
        assertEquals("content", uri.scheme)
        assertEquals(authority, uri.authority)
        assertNotEquals("秘密.backup", uri.lastPathSegment)
        assertEquals(spec.mimeType, activity.contentResolver.getType(uri))
        activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)!!.use {
            assertTrue(it.moveToFirst())
            assertEquals("秘密.backup", it.getString(0))
            assertEquals(bytes.size.toLong(), it.getLong(1))
        }
        owner.close()
        assertArrayEquals(bytes, activity.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        assertEquals(emptyList<Event>(), events)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(emptyList<Any>(), owner.state.value.effects)
    }

    @Test
    fun providerExposesOnlyOwnedCacheFilesAndRefusesWriteAndDelete() {
        val info = activity.packageManager.resolveContentProvider(authority, PackageManager.GET_META_DATA)!!
        assertFalse(info.exported)
        assertTrue(info.grantUriPermissions)
        val outside = File(activity.cacheDir, "private-key-material").apply { writeText("preserved") }
        assertThrows(IllegalArgumentException::class.java) { FileProvider.getUriForFile(activity, authority, outside) }
        runBlocking { assertTrue(owner().executeAsync(Command.ExportFile(spec))) }
        val uri = sharedUri()
        assertThrows(FileNotFoundException::class.java) { provider.openFile(uri, "rw") }
        assertThrows(FileNotFoundException::class.java) { provider.openFile(uri, "w") }
        assertThrows(UnsupportedOperationException::class.java) { provider.delete(uri, null, null) }
        assertArrayEquals(bytes, activity.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        assertEquals("preserved", outside.readText())
        assertEquals(emptyList<Event>(), events)
    }

    @Test
    fun diskWriteUsesTheNativeWorkerAndKeepsThePreparedBytesUnchanged() {
        val usedMain = AtomicReference<Boolean>()
        val host =
            ActivityFileExportHost(activity, { activity }, FileExportLimits(), { System.currentTimeMillis() }) {
                usedMain.set(Looper.myLooper() == Looper.getMainLooper())
                FileOutputStream(it)
            }
        runBlocking { assertTrue(owner(host).executeAsync(Command.ExportFile(spec))) }
        assertEquals(false, usedMain.get())
        val uri = sharedUri()
        assertArrayEquals(bytes, activity.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        assertEquals(emptyList<Event>(), events)
    }
}
