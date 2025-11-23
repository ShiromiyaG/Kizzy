package com.my.kizzy.feature_rpc_base.components

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.my.kizzy.feature_rpc_base.AccessibilityHelper
import com.my.kizzy.feature_rpc_base.services.ForegroundAppDetector

@Composable
fun AccessibilityPermissionCard(context: Context = LocalContext.current) {
    var isEnabled by remember(context) { 
        mutableStateOf(isServiceEnabled(context))
    }

    LaunchedEffect(context) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            isEnabled = isServiceEnabled(context)
        }
    }

    if (!isEnabled) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "⚠️ Detecção Melhorada Disponível",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = "Ative o serviço de acessibilidade para detecção instantânea de apps.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Button(
                    onClick = { AccessibilityHelper.openAccessibilitySettings(context) },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Abrir Configurações")
                }
            }
        }
    }
}

private fun isServiceEnabled(context: Context): Boolean {
    return AccessibilityHelper.isAccessibilityServiceEnabled(
        context, 
        ForegroundAppDetector::class.java
    )
}
