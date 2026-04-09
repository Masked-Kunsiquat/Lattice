package com.github.maskedkunisquat.lattice

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Thin wrapper around [BiometricPrompt] for use in a single-Activity Compose app.
 *
 * ## Auth policy
 * Accepts Class 3 (strong) biometrics — fingerprint, face, iris — and falls back to
 * device credential (PIN / pattern / password) if no biometric is enrolled.
 * If the device has no lock screen at all, [canAuthenticate] returns false and the
 * app runs ungated rather than blocking the user permanently.
 *
 * ## Re-lock
 * [MainActivity] resets the unlock flag on [androidx.lifecycle.Lifecycle.Event.ON_STOP]
 * so the gate re-engages whenever the app leaves the foreground.
 */
class BiometricGate(private val activity: FragmentActivity) {

    private val authenticators = BIOMETRIC_STRONG or DEVICE_CREDENTIAL

    /**
     * Returns true if the device can authenticate with at least device credential.
     * When false, MainActivity skips the gate entirely — better than a permanent block.
     */
    fun canAuthenticate(): Boolean =
        BiometricManager.from(activity)
            .canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS

    /**
     * Shows the system biometric / credential prompt.
     *
     * [onSuccess] is called on the main thread when authentication succeeds.
     * [onError] is called on the main thread for unrecoverable errors (hardware failure,
     * lockout, etc.). User-initiated cancels (back press) are silently ignored so the
     * LockScreen can re-prompt via its Unlock button.
     */
    fun authenticate(onSuccess: () -> Unit, onError: (String) -> Unit = {}) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult
                ) { onSuccess() }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // User cancelled (back press or negative button) — not an error; LockScreen
                    // stays visible and the Unlock button lets them re-try.
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    ) {
                        onError(errString.toString())
                    }
                }

                // onAuthenticationFailed: biometric not recognised — BiometricPrompt retries
                // automatically; no action needed here.
            },
        )

        // NOTE: setNegativeButtonText must NOT be called when DEVICE_CREDENTIAL is included
        // in allowedAuthenticators — the system provides its own cancel / switch-to-PIN UI.
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Lattice")
            .setSubtitle("Use your fingerprint or device credential")
            .setAllowedAuthenticators(authenticators)
            .build()

        prompt.authenticate(promptInfo)
    }
}
