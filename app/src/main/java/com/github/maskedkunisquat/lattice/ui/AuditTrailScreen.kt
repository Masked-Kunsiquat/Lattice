package com.github.maskedkunisquat.lattice.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.github.maskedkunisquat.lattice.LatticeApplication
import com.github.maskedkunisquat.lattice.core.data.model.TransitEvent
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── ViewModel ─────────────────────────────────────────────────────────────────

class AuditTrailViewModel(app: LatticeApplication) : ViewModel() {

    val events: StateFlow<List<TransitEvent>> =
        app.database.transitEventDao().getEventsFlow().stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()
        )

    companion object {
        fun factory(app: LatticeApplication) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AuditTrailViewModel(app) as T
        }
    }
}

// ── Screen ────────────────────────────────────────────────────────────────────

private val timestampFmt = SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault())

private fun formatProvider(raw: String) = when (raw) {
    "cloud_claude"      -> "Claude (Cloud)"
    "llama3_onnx_local" -> "Local (Llama 3)"
    else                -> raw.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

@Composable
fun AuditTrailScreen(
    viewModel: AuditTrailViewModel,
    modifier: Modifier = Modifier,
) {
    val events by viewModel.events.collectAsStateWithLifecycle()

    if (events.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .testTag("audit:empty"),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "No data has left this device",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            items(events, key = { it.id }) { event ->
                TransitEventRow(event)
            }
        }
    }
}

@Composable
private fun TransitEventRow(event: TransitEvent) {
    ListItem(
        overlineContent = {
            Text(
                timestampFmt.format(Date(event.timestamp)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        headlineContent = { Text(formatProvider(event.providerName)) },
        supportingContent = {
            Text(
                event.operationType.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}
