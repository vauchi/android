package com.vauchi.ui.onboarding

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Onboarding step enum matching iOS implementation
 */
enum class OnboardingStep {
    Welcome,
    CreateIdentity,
    AddFields,
    Preview,
    Security,
    Ready
}

/**
 * Data collected during onboarding
 */
data class OnboardingData(
    val displayName: String = "",
    val phone: String = "",
    val email: String = ""
)

/**
 * Main onboarding screen composable
 * Based on: features/onboarding.feature
 */
@Composable
fun OnboardingScreen(
    onComplete: (displayName: String, phone: String?, email: String?) -> Unit,
    onRestore: () -> Unit = {}
) {
    var currentStep by remember { mutableStateOf(OnboardingStep.Welcome) }
    var onboardingData by remember { mutableStateOf(OnboardingData()) }

    AnimatedContent(
        targetState = currentStep,
        transitionSpec = {
            slideInHorizontally { it } + fadeIn() togetherWith
                    slideOutHorizontally { -it } + fadeOut()
        },
        label = "onboarding_step"
    ) { step ->
        when (step) {
            OnboardingStep.Welcome -> WelcomeStep(
                onContinue = { currentStep = OnboardingStep.CreateIdentity },
                onRestore = onRestore
            )
            OnboardingStep.CreateIdentity -> CreateIdentityStep(
                displayName = onboardingData.displayName,
                onDisplayNameChange = { onboardingData = onboardingData.copy(displayName = it) },
                onContinue = { currentStep = OnboardingStep.AddFields },
                onBack = { currentStep = OnboardingStep.Welcome }
            )
            OnboardingStep.AddFields -> AddFieldsStep(
                phone = onboardingData.phone,
                email = onboardingData.email,
                onPhoneChange = { onboardingData = onboardingData.copy(phone = it) },
                onEmailChange = { onboardingData = onboardingData.copy(email = it) },
                onContinue = { currentStep = OnboardingStep.Preview },
                onBack = { currentStep = OnboardingStep.CreateIdentity },
                onSkip = { currentStep = OnboardingStep.Preview }
            )
            OnboardingStep.Preview -> PreviewStep(
                data = onboardingData,
                onContinue = { currentStep = OnboardingStep.Security },
                onBack = { currentStep = OnboardingStep.AddFields }
            )
            OnboardingStep.Security -> SecurityStep(
                onContinue = {
                    onComplete(
                        onboardingData.displayName,
                        onboardingData.phone.takeIf { it.isNotBlank() },
                        onboardingData.email.takeIf { it.isNotBlank() }
                    )
                    currentStep = OnboardingStep.Ready
                },
                onBack = { currentStep = OnboardingStep.Preview }
            )
            OnboardingStep.Ready -> ReadyStep()
        }
    }
}

// Progress indicator
@Composable
fun OnboardingProgress(currentStep: Int, totalSteps: Int) {
    Row(
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        repeat(totalSteps) { index ->
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (index < currentStep) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
            )
            if (index < totalSteps - 1) {
                Spacer(modifier = Modifier.width(8.dp))
            }
        }
    }
}

// Welcome Step
@Composable
fun WelcomeStep(
    onContinue: () -> Unit,
    onRestore: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.weight(1f))

        // Hero section
        Box(
            modifier = Modifier
                .size(100.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.tertiary
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Contacts,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = Color.White
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            "Vauchi",
            style = MaterialTheme.typography.displaySmall
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            "Contact cards that stay up to date",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.weight(1f))

        // Value propositions
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ValuePropRow(
                icon = Icons.Default.QrCodeScanner,
                title = "Exchange in person",
                description = "Scan QR codes to connect"
            )
            ValuePropRow(
                icon = Icons.Default.Sync,
                title = "Auto-updating",
                description = "Your info stays current"
            )
            ValuePropRow(
                icon = Icons.Default.Lock,
                title = "Private & secure",
                description = "End-to-end encrypted"
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Actions
        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Get Started")
        }

        Spacer(modifier = Modifier.height(12.dp))

        TextButton(onClick = onRestore) {
            Text("I have a backup")
        }
    }
}

@Composable
fun ValuePropRow(
    icon: ImageVector,
    title: String,
    description: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// Create Identity Step
@Composable
fun CreateIdentityStep(
    displayName: String,
    onDisplayNameChange: (String) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val isValid = displayName.isNotBlank()

    LaunchedEffect(Unit) {
        delay(300)
        focusRequester.requestFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        // Back button
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }

        OnboardingProgress(currentStep = 1, totalSteps = 4)

        Spacer(modifier = Modifier.weight(1f))

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                Icons.Default.Person,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                "What's your name?",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                "This is how you'll appear to your contacts",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            OutlinedTextField(
                value = displayName,
                onValueChange = onDisplayNameChange,
                label = { Text("Your name") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (isValid) onContinue() })
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onContinue,
            enabled = isValid,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Continue")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            "You can change this later",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
}

// Add Fields Step
@Composable
fun AddFieldsStep(
    phone: String,
    email: String,
    onPhoneChange: (String) -> Unit,
    onEmailChange: (String) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
    onSkip: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }

        OnboardingProgress(currentStep = 2, totalSteps = 4)

        Spacer(modifier = Modifier.height(24.dp))

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                Icons.Default.AddCircle,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                "Add your info",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                "Help contacts reach you",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = phone,
            onValueChange = onPhoneChange,
            label = { Text("Phone") },
            leadingIcon = { Icon(Icons.Default.Phone, contentDescription = null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = email,
            onValueChange = onEmailChange,
            label = { Text("Email") },
            leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            "You can add more fields later in your card settings",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Continue")
        }

        Spacer(modifier = Modifier.height(12.dp))

        TextButton(
            onClick = onSkip,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text("Skip for now")
        }
    }
}

// Preview Step
@Composable
fun PreviewStep(
    data: OnboardingData,
    onContinue: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }

        OnboardingProgress(currentStep = 3, totalSteps = 4)

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            "Your card",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            "This is how you'll appear to contacts",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Card preview
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp)
            ) {
                // Avatar
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.tertiary
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        data.displayName.take(1).uppercase(),
                        style = MaterialTheme.typography.headlineLarge,
                        color = Color.White
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    data.displayName,
                    style = MaterialTheme.typography.titleLarge
                )

                if (data.phone.isNotBlank() || data.email.isNotBlank()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(16.dp))
                }

                if (data.phone.isNotBlank()) {
                    PreviewFieldRow(
                        icon = Icons.Default.Phone,
                        label = "Phone",
                        value = data.phone
                    )
                }

                if (data.email.isNotBlank()) {
                    PreviewFieldRow(
                        icon = Icons.Default.Email,
                        label = "Email",
                        value = data.email
                    )
                }

                if (data.phone.isBlank() && data.email.isBlank()) {
                    Text(
                        "No additional info yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Check, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Looks good!")
        }

        Spacer(modifier = Modifier.height(12.dp))

        TextButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text("Edit card")
        }
    }
}

@Composable
fun PreviewFieldRow(
    icon: ImageVector,
    label: String,
    value: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// Security Step
@Composable
fun SecurityStep(
    onContinue: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }

        OnboardingProgress(currentStep = 4, totalSteps = 4)

        Spacer(modifier = Modifier.weight(1f))

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Security diagram
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(16.dp)
                    )
                    .padding(24.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.PhoneAndroid,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text("You", style = MaterialTheme.typography.labelSmall)
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.SyncAlt,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = Color(0xFF4CAF50)
                        )
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = Color(0xFF4CAF50)
                        )
                    }
                    Text(
                        "Encrypted",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF4CAF50)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.PhoneAndroid,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text("Contact", style = MaterialTheme.typography.labelSmall)
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                "Your info is private",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                "Only you and your contacts can see your information. Not even us.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Key points
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SecurityPoint(
                    icon = Icons.Default.Shield,
                    title = "End-to-end encrypted",
                    description = "Your data is encrypted on your device"
                )
                SecurityPoint(
                    icon = Icons.Default.People,
                    title = "Direct to contacts",
                    description = "Updates go straight to your contacts"
                )
                SecurityPoint(
                    icon = Icons.Default.VisibilityOff,
                    title = "No central database",
                    description = "We can't see your information"
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Finish setup")
        }
    }
}

@Composable
fun SecurityPoint(
    icon: ImageVector,
    title: String,
    description: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = Color(0xFF4CAF50)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// Ready Step
@Composable
fun ReadyStep() {
    var showCheck by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(200)
        showCheck = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.weight(1f))

        // Success animation
        AnimatedVisibility(
            visible = showCheck,
            enter = scaleIn() + fadeIn()
        ) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF4CAF50).copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(80.dp),
                    tint = Color(0xFF4CAF50)
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            "You're all set!",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            "Your card is ready to share",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.weight(1f))

        // Next steps
        Text(
            "What's next?",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        NextStepCard(
            icon = Icons.Default.QrCodeScanner,
            title = "Exchange with someone",
            description = "Find a friend nearby and scan each other's QR codes"
        )

        Spacer(modifier = Modifier.height(12.dp))

        NextStepCard(
            icon = Icons.Default.AddCircle,
            title = "Add more info",
            description = "Customize your card with more contact details"
        )

        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
fun NextStepCard(
    icon: ImageVector,
    title: String,
    description: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
