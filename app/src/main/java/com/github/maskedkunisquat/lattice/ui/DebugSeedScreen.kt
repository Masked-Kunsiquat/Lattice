package com.github.maskedkunisquat.lattice.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.maskedkunisquat.lattice.core.data.seed.SeedPersona

@Composable
fun DebugSeedScreen(viewModel: DebugSeedViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                Text(
                    "Seed Data",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }

            item {
                PersonaSeedRow(
                    name = "Holmes",
                    subtitle = "Reality Testing · Q2 (TENSE/ANGRY)",
                    entryCount = state.holmesCount,
                    isLoading = state.loadingPersona == SeedPersona.HOLMES,
                    anyLoading = state.loadingPersona != null,
                    onSeed = { viewModel.seed(SeedPersona.HOLMES) },
                    onClear = { viewModel.clear(SeedPersona.HOLMES) },
                )
            }

            item {
                PersonaSeedRow(
                    name = "Watson",
                    subtitle = "Behavioral Activation · Q3 (DEPRESSED/FATIGUED)",
                    entryCount = state.watsonCount,
                    isLoading = state.loadingPersona == SeedPersona.WATSON,
                    anyLoading = state.loadingPersona != null,
                    onSeed = { viewModel.seed(SeedPersona.WATSON) },
                    onClear = { viewModel.clear(SeedPersona.WATSON) },
                )
            }

            item {
                PersonaSeedRow(
                    name = "Werther",
                    subtitle = "Emotional Reasoning · Mixed valence",
                    entryCount = state.wertherCount,
                    isLoading = state.loadingPersona == SeedPersona.WERTHER,
                    anyLoading = state.loadingPersona != null,
                    onSeed = { viewModel.seed(SeedPersona.WERTHER) },
                    onClear = { viewModel.clear(SeedPersona.WERTHER) },
                )
            }

            item {
                val totalSeeded = state.holmesCount + state.watsonCount + state.wertherCount
                Button(
                    onClick = viewModel::clearAll,
                    enabled = totalSeeded > 0 && state.loadingPersona == null,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text("Clear All Seeds")
                }
            }
        }
    }
}

@Composable
private fun PersonaSeedRow(
    name: String,
    subtitle: String,
    entryCount: Int,
    isLoading: Boolean,
    anyLoading: Boolean,
    onSeed: () -> Unit,
    onClear: () -> Unit,
) {
    Surface {
        ListItem(
            headlineContent = { Text(name) },
            supportingContent = {
                Text(
                    if (entryCount > 0) "$subtitle · $entryCount entries" else subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingContent = {
                if (isLoading) {
                    Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End,
                    ) {
                        OutlinedButton(
                            onClick = onSeed,
                            enabled = !anyLoading,
                        ) { Text("Seed") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = onClear,
                            enabled = !anyLoading && entryCount > 0,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) { Text("Clear") }
                    }
                }
            },
        )
    }
}
