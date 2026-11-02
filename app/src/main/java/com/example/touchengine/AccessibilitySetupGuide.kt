package com.example.touchengine

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** In-app guidance; system permission is always granted by the user in Android Settings. */
@Composable
internal fun AccessibilitySetupGuide(
    strings: AccessibilityGuideStrings,
    appName: String,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAppInfo: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.title) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(if (enabled) strings.enabled else strings.disabled, fontWeight = FontWeight.Bold)
                Text(strings.introduction)
                strings.steps.forEachIndexed { index, step ->
                    Text("${index + 1}. ${step.replace("{app}", appName)}")
                }
                HorizontalDivider()
                Text(strings.restrictedTitle, fontWeight = FontWeight.Bold)
                Text(strings.restrictedDescription)
                OutlinedButton(onClick = onOpenAppInfo, modifier = Modifier.fillMaxWidth()) {
                    Text(strings.openAppInfo)
                }
            }
        },
        confirmButton = { TextButton(onClick = onOpenSettings) { Text(strings.openSettings) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.close) } }
    )
}
