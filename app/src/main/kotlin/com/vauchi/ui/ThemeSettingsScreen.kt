// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vauchi.util.ThemeManager
import com.vauchi.util.hexToColor
import uniffi.vauchi_mobile.MobileTheme
import uniffi.vauchi_mobile.MobileThemeMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val themeManager = remember { ThemeManager.getInstance(context) }
    val isDarkMode = isSystemInDarkTheme()

    // Observe theme manager state
    val currentTheme = themeManager.currentTheme
    val followSystem = themeManager.followSystem
    val darkThemes = themeManager.darkThemes
    val lightThemes = themeManager.lightThemes

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Theme") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Follow System Section
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Follow System", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Match device appearance",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = followSystem,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                themeManager.resetToSystem(isDarkMode)
                            }
                        }
                    )
                }
            }

            // Theme Selection (only shown when not following system)
            if (!followSystem) {
                // Dark Themes
                Text("Dark Themes", style = MaterialTheme.typography.titleMedium)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    darkThemes.forEach { theme ->
                        ThemeCard(
                            theme = theme,
                            isSelected = themeManager.selectedThemeId == theme.id,
                            onClick = { themeManager.selectTheme(theme.id, isDarkMode) }
                        )
                    }
                }

                HorizontalDivider()

                // Light Themes
                Text("Light Themes", style = MaterialTheme.typography.titleMedium)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    lightThemes.forEach { theme ->
                        ThemeCard(
                            theme = theme,
                            isSelected = themeManager.selectedThemeId == theme.id,
                            onClick = { themeManager.selectTheme(theme.id, isDarkMode) }
                        )
                    }
                }
            }

            // Preview
            currentTheme?.let { theme ->
                HorizontalDivider()
                Text("Preview", style = MaterialTheme.typography.titleMedium)
                ThemePreviewCard(theme = theme)
            }
        }
    }
}

@Composable
private fun ThemeCard(
    theme: MobileTheme,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Color swatches
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ColorSwatch(theme.colors.bgPrimary)
                ColorSwatch(theme.colors.accent)
                ColorSwatch(theme.colors.textPrimary)
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(theme.name, style = MaterialTheme.typography.bodyLarge)
                theme.author?.let { author ->
                    Text(
                        "by $author",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (isSelected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun ColorSwatch(hex: String) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(hexToColor(hex))
            .border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
    )
}

@Composable
private fun ThemePreviewCard(theme: MobileTheme) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = hexToColor(theme.colors.bgPrimary)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    theme.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = hexToColor(theme.colors.textPrimary)
                )
                Text(
                    if (theme.mode == MobileThemeMode.DARK) "Dark" else "Light",
                    style = MaterialTheme.typography.labelSmall,
                    color = hexToColor(theme.colors.textSecondary),
                    modifier = Modifier
                        .background(
                            hexToColor(theme.colors.bgSecondary),
                            RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            Text(
                "Sample text with primary color",
                color = hexToColor(theme.colors.textPrimary)
            )
            Text(
                "Secondary text color",
                style = MaterialTheme.typography.bodySmall,
                color = hexToColor(theme.colors.textSecondary)
            )

            // Color palette
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ColorPill("Accent", theme.colors.accent)
                ColorPill("Success", theme.colors.success)
                ColorPill("Error", theme.colors.error)
                ColorPill("Warning", theme.colors.warning)
            }
        }
    }
}

@Composable
private fun ColorPill(label: String, hex: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(hexToColor(hex))
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
