package com.vauchi.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import uniffi.vauchi_mobile.MobileAhaMoment
import uniffi.vauchi_mobile.MobileAhaMomentType

/**
 * Dialog for displaying an "Aha moment" - a progressive onboarding hint at key milestones.
 */
@Composable
fun AhaMomentDialog(
    moment: MobileAhaMoment,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Icon with optional animation
                if (moment.hasAnimation) {
                    AnimatedIcon(momentType = moment.momentType)
                } else {
                    MomentIcon(momentType = moment.momentType)
                }

                // Title
                Text(
                    text = moment.title,
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center
                )

                // Message
                Text(
                    text = moment.message,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Dismiss button
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Got it!")
                }
            }
        }
    }
}

@Composable
private fun MomentIcon(momentType: MobileAhaMomentType) {
    val icon = getMomentIcon(momentType)
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(64.dp),
        tint = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun AnimatedIcon(momentType: MobileAhaMomentType) {
    val infiniteTransition = rememberInfiniteTransition(label = "aha_animation")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale_animation"
    )

    val icon = getMomentIcon(momentType)
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier
            .size(64.dp)
            .scale(scale),
        tint = MaterialTheme.colorScheme.primary
    )
}

private fun getMomentIcon(momentType: MobileAhaMomentType): ImageVector {
    return when (momentType) {
        MobileAhaMomentType.CARD_CREATION_COMPLETE -> Icons.Default.CheckCircle
        MobileAhaMomentType.FIRST_EDIT -> Icons.Default.Edit
        MobileAhaMomentType.FIRST_CONTACT_ADDED -> Icons.Default.PersonAdd
        MobileAhaMomentType.FIRST_UPDATE_RECEIVED -> Icons.Default.Download
        MobileAhaMomentType.FIRST_OUTBOUND_DELIVERED -> Icons.Default.Send
    }
}

/**
 * Composable that displays an aha moment dialog when the moment is non-null.
 */
@Composable
fun AhaMomentOverlay(
    moment: MobileAhaMoment?,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    Box {
        content()

        if (moment != null) {
            AhaMomentDialog(
                moment = moment,
                onDismiss = onDismiss
            )
        }
    }
}
