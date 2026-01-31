// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.ui.model

import org.junit.Assert.*
import org.junit.Test

class ContentUpdateTest {

    // --- ContentUpdateType ---

    @Test
    fun `ContentUpdateType has four values`() {
        val values = ContentUpdateType.entries
        assertEquals(4, values.size)
        assertTrue(values.contains(ContentUpdateType.Networks))
        assertTrue(values.contains(ContentUpdateType.Locales))
        assertTrue(values.contains(ContentUpdateType.Themes))
        assertTrue(values.contains(ContentUpdateType.Help))
    }

    // --- ContentUpdateStatus ---

    @Test
    fun `UpToDate is a ContentUpdateStatus`() {
        val status: ContentUpdateStatus = ContentUpdateStatus.UpToDate
        assertTrue(status is ContentUpdateStatus.UpToDate)
    }

    @Test
    fun `UpdatesAvailable carries types list`() {
        val types = listOf(ContentUpdateType.Networks, ContentUpdateType.Themes)
        val status = ContentUpdateStatus.UpdatesAvailable(types)
        assertEquals(types, status.types)
    }

    @Test
    fun `CheckFailed carries error message`() {
        val status = ContentUpdateStatus.CheckFailed("timeout")
        assertEquals("timeout", status.error)
    }

    @Test
    fun `Disabled is a ContentUpdateStatus`() {
        val status: ContentUpdateStatus = ContentUpdateStatus.Disabled
        assertTrue(status is ContentUpdateStatus.Disabled)
    }

    // --- ContentApplyResult ---

    @Test
    fun `NoUpdates is a ContentApplyResult`() {
        val result: ContentApplyResult = ContentApplyResult.NoUpdates
        assertTrue(result is ContentApplyResult.NoUpdates)
    }

    @Test
    fun `Applied carries applied and failed lists`() {
        val applied = listOf(ContentUpdateType.Networks)
        val failed = listOf(ContentUpdateType.Themes)
        val result = ContentApplyResult.Applied(applied, failed)
        assertEquals(applied, result.applied)
        assertEquals(failed, result.failed)
    }

    @Test
    fun `Applied with empty lists`() {
        val result = ContentApplyResult.Applied(emptyList(), emptyList())
        assertTrue(result.applied.isEmpty())
        assertTrue(result.failed.isEmpty())
    }

    @Test
    fun `ContentApplyResult Disabled is a ContentApplyResult`() {
        val result: ContentApplyResult = ContentApplyResult.Disabled
        assertTrue(result is ContentApplyResult.Disabled)
    }

    @Test
    fun `ContentApplyResult Error carries error message`() {
        val result = ContentApplyResult.Error("network failure")
        assertEquals("network failure", result.error)
    }

    @Test
    fun `ContentUpdateStatus subtypes are distinguishable via when`() {
        val statuses = listOf(
            ContentUpdateStatus.UpToDate,
            ContentUpdateStatus.UpdatesAvailable(listOf(ContentUpdateType.Help)),
            ContentUpdateStatus.CheckFailed("err"),
            ContentUpdateStatus.Disabled
        )
        val labels = statuses.map { status ->
            when (status) {
                is ContentUpdateStatus.UpToDate -> "up-to-date"
                is ContentUpdateStatus.UpdatesAvailable -> "available"
                is ContentUpdateStatus.CheckFailed -> "failed"
                is ContentUpdateStatus.Disabled -> "disabled"
            }
        }
        assertEquals(listOf("up-to-date", "available", "failed", "disabled"), labels)
    }
}
