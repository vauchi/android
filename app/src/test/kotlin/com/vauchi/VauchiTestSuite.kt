// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi

import org.junit.runner.RunWith
import org.junit.runners.Suite

@RunWith(Suite::class)
@Suite.SuiteClasses(
    com.vauchi.data.VauchiRepositoryTest::class,
    com.vauchi.data.KeyStoreHelperTest::class,
    com.vauchi.ui.MainViewModelTest::class,
    com.vauchi.util.ClipboardUtilsTest::class,
    com.vauchi.util.NetworkMonitorTest::class,
    com.vauchi.util.ContactActionsTest::class
)
class VauchiTestSuite
