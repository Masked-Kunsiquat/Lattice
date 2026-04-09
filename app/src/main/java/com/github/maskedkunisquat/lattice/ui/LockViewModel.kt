package com.github.maskedkunisquat.lattice.ui

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Survives configuration changes (screen rotation) so a successful biometric auth is
 * not lost when the user rotates the device while the app is unlocked.
 *
 * Note: [BiometricGate] cannot live here because [BiometricPrompt] requires a
 * [androidx.fragment.app.FragmentActivity] reference — that stays in [MainActivity].
 * This VM only holds the derived UI state and exposes callbacks that MainActivity
 * calls after auth outcomes.
 */
class LockViewModel : ViewModel() {

    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    fun onUnlocked() {
        _isUnlocked.value = true
        _authError.value = null
    }

    /** Called on [androidx.lifecycle.Lifecycle.Event.ON_STOP] to re-engage the gate. */
    fun onAppStopped() {
        _isUnlocked.value = false
    }

    /** Called when [BiometricGate] reports an unrecoverable error (lockout, hardware failure). */
    fun onAuthError(message: String) {
        _authError.value = message
    }
}
