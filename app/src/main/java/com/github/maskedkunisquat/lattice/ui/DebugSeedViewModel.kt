package com.github.maskedkunisquat.lattice.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.github.maskedkunisquat.lattice.LatticeApplication
import com.github.maskedkunisquat.lattice.core.data.seed.SeedManager
import com.github.maskedkunisquat.lattice.core.data.seed.SeedPersona
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DebugSeedUiState(
    val holmesCount: Int = 0,
    val watsonCount: Int = 0,
    val wertherCount: Int = 0,
    val loadingPersona: SeedPersona? = null,
    val errorMessage: String? = null,
)

class DebugSeedViewModel(private val seedManager: SeedManager) : ViewModel() {

    private val _uiState = MutableStateFlow(DebugSeedUiState())
    val uiState: StateFlow<DebugSeedUiState> = _uiState.asStateFlow()

    init {
        refreshCounts()
    }

    fun seed(persona: SeedPersona) {
        if (_uiState.value.loadingPersona != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(loadingPersona = persona, errorMessage = null) }
            try {
                seedManager.seedPersona(persona)
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Seed failed") }
            } finally {
                refreshCounts()
                _uiState.update { it.copy(loadingPersona = null) }
            }
        }
    }

    fun clear(persona: SeedPersona) {
        if (_uiState.value.loadingPersona != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(loadingPersona = persona, errorMessage = null) }
            try {
                seedManager.clearPersona(persona)
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Clear failed") }
            } finally {
                refreshCounts()
                _uiState.update { it.copy(loadingPersona = null) }
            }
        }
    }

    fun clearAll() {
        if (_uiState.value.loadingPersona != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(errorMessage = null) }
            for (persona in SeedPersona.entries) {
                _uiState.update { it.copy(loadingPersona = persona) }
                try {
                    seedManager.clearPersona(persona)
                } catch (e: Exception) {
                    _uiState.update { it.copy(errorMessage = e.message ?: "Clear failed for ${persona.name}") }
                    break
                }
            }
            refreshCounts()
            _uiState.update { it.copy(loadingPersona = null) }
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun refreshCounts() {
        _uiState.update {
            it.copy(
                holmesCount = seedManager.getSeededEntryCount(SeedPersona.HOLMES),
                watsonCount = seedManager.getSeededEntryCount(SeedPersona.WATSON),
                wertherCount = seedManager.getSeededEntryCount(SeedPersona.WERTHER),
            )
        }
    }

    companion object {
        fun factory(app: LatticeApplication) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                DebugSeedViewModel(app.seedManager) as T
        }
    }
}
