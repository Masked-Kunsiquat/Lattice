package com.github.maskedkunisquat.lattice

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import com.github.maskedkunisquat.lattice.ui.AppNavHost
import com.github.maskedkunisquat.lattice.ui.LockScreen
import com.github.maskedkunisquat.lattice.ui.LockViewModel
import com.github.maskedkunisquat.lattice.ui.theme.LatticeTheme

class MainActivity : FragmentActivity() {

    private lateinit var biometricGate: BiometricGate

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        biometricGate = BiometricGate(this)
        val lockViewModel = ViewModelProvider(this)[LockViewModel::class.java]

        // Re-lock whenever the app leaves the foreground.
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) lockViewModel.onAppStopped()
        })

        val app = application as LatticeApplication
        setContent {
            LatticeTheme {
                val isUnlocked by lockViewModel.isUnlocked.collectAsStateWithLifecycle()
                val authError by lockViewModel.authError.collectAsStateWithLifecycle()

                // Skip the gate on devices with no lock screen — better than a permanent block.
                if (!biometricGate.canAuthenticate() || isUnlocked) {
                    AppNavHost(app = app)
                } else {
                    LockScreen(
                        authError = authError,
                        onUnlockClick = {
                            biometricGate.authenticate(
                                onSuccess = { lockViewModel.onUnlocked() },
                                onError = { error -> lockViewModel.onAuthError(error) },
                            )
                        },
                    )
                }
            }
        }
    }
}
