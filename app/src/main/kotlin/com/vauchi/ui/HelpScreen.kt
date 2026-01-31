// SPDX-FileCopyrightText: 2026 Mattia Egloff <mattia.egloff@pm.me>
//
// SPDX-License-Identifier: GPL-3.0-or-later

package com.vauchi.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.vauchi.util.LocalizationManager
import uniffi.vauchi_mobile.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val localizationManager = remember { LocalizationManager.getInstance(context) }

    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<MobileHelpCategory?>(null) }
    var expandedFaqId by remember { mutableStateOf<String?>(null) }

    val categories = remember { getHelpCategories() }
    val allFaqs = remember { getFaqs() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(localizationManager.t("help.title")) },
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Search
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search FAQs") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
            )

            // Content based on state
            when {
                searchQuery.isNotEmpty() -> {
                    val results = searchFaqs(searchQuery)
                    SearchResultsSection(
                        query = searchQuery,
                        results = results,
                        expandedFaqId = expandedFaqId,
                        onToggleFaq = { id ->
                            expandedFaqId = if (expandedFaqId == id) null else id
                        }
                    )
                }
                selectedCategory != null -> {
                    CategoryFaqsSection(
                        category = selectedCategory!!,
                        categories = categories,
                        expandedFaqId = expandedFaqId,
                        onBack = { selectedCategory = null },
                        onToggleFaq = { id ->
                            expandedFaqId = if (expandedFaqId == id) null else id
                        }
                    )
                }
                else -> {
                    CategoriesSection(
                        categories = categories,
                        allFaqs = allFaqs,
                        onSelectCategory = { selectedCategory = it }
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoriesSection(
    categories: List<MobileHelpCategoryInfo>,
    allFaqs: List<MobileFaqItem>,
    onSelectCategory: (MobileHelpCategory) -> Unit
) {
    Text("Categories", style = MaterialTheme.typography.titleMedium)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        categories.forEach { categoryInfo ->
            val count = allFaqs.count { it.category == categoryInfo.category }
            CategoryCard(
                category = categoryInfo.category,
                displayName = categoryInfo.displayName,
                faqCount = count,
                onClick = { onSelectCategory(categoryInfo.category) }
            )
        }
    }
}

@Composable
private fun CategoryCard(
    category: MobileHelpCategory,
    displayName: String,
    faqCount: Int,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    getCategoryIcon(category),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(displayName, style = MaterialTheme.typography.bodyLarge)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "$faqCount",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CategoryFaqsSection(
    category: MobileHelpCategory,
    categories: List<MobileHelpCategoryInfo>,
    expandedFaqId: String?,
    onBack: () -> Unit,
    onToggleFaq: (String) -> Unit
) {
    val faqs = remember(category) { getFaqsByCategory(category) }
    val displayName = categories.find { it.category == category }?.displayName ?: ""

    // Back button
    TextButton(onClick = onBack) {
        Icon(Icons.Default.ChevronLeft, contentDescription = null)
        Spacer(Modifier.width(4.dp))
        Text("All Categories")
    }

    Text(displayName, style = MaterialTheme.typography.titleMedium)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        faqs.forEach { faq ->
            FaqCard(
                faq = faq,
                isExpanded = expandedFaqId == faq.id,
                onToggle = { onToggleFaq(faq.id) }
            )
        }
    }
}

@Composable
private fun SearchResultsSection(
    query: String,
    results: List<MobileFaqItem>,
    expandedFaqId: String?,
    onToggleFaq: (String) -> Unit
) {
    Text(
        "Search Results (${results.size})",
        style = MaterialTheme.typography.titleMedium
    )

    if (results.isEmpty()) {
        Text(
            "No results found for \"$query\"",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            results.forEach { faq ->
                FaqCard(
                    faq = faq,
                    isExpanded = expandedFaqId == faq.id,
                    onToggle = { onToggleFaq(faq.id) }
                )
            }
        }
    }
}

@Composable
private fun FaqCard(
    faq: MobileFaqItem,
    isExpanded: Boolean,
    onToggle: () -> Unit
) {
    Card(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Help,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        faq.question,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                Icon(
                    if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier.padding(start = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        faq.answer,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Related FAQs
                    if (faq.related.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "Related:",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            faq.related.forEach { relatedId ->
                                val relatedFaq = getFaqById(relatedId)
                                relatedFaq?.let {
                                    Text(
                                        "• ${it.question}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun getCategoryIcon(category: MobileHelpCategory): ImageVector {
    return when (category) {
        MobileHelpCategory.GETTING_STARTED -> Icons.Default.Star
        MobileHelpCategory.PRIVACY -> Icons.Default.Lock
        MobileHelpCategory.RECOVERY -> Icons.Default.Refresh
        MobileHelpCategory.CONTACTS -> Icons.Default.People
        MobileHelpCategory.UPDATES -> Icons.Default.Sync
        MobileHelpCategory.FEATURES -> Icons.Default.AutoAwesome
    }
}
