package com.github.maskedkunisquat.lattice

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.github.maskedkunisquat.lattice.ui.AppNavHost
import com.github.maskedkunisquat.lattice.ui.LockScreen
import com.github.maskedkunisquat.lattice.ui.theme.LatticeTheme

class MainActivity : FragmentActivity() {

    private var isUnlocked by mutableStateOf(false)
    private lateinit var biometricGate: BiometricGate

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        biometricGate = BiometricGate(this)

        // Re-lock whenever the app leaves the foreground (home button, recent apps, etc.)
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) isUnlocked = false
        })

        val app = application as LatticeApplication
        setContent {
            LatticeTheme {
                // Skip the gate on devices with no lock screen — better than a permanent block.
                if (!biometricGate.canAuthenticate() || isUnlocked) {
                    AppNavHost(app = app)
                } else {
                    LockScreen(
                        onUnlockClick = {
                            biometricGate.authenticate(
                                onSuccess = { isUnlocked = true },
                            )
                        },
                    )
                }
            }
        }
    }
}
