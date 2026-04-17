package com.github.maskedkunisquat.lattice

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import com.github.maskedkunisquat.lattice.ui.AppNavHost
import com.github.maskedkunisquat.lattice.ui.LockScreen
import com.github.maskedkunisquat.lattice.ui.LockViewModel
import com.github.maskedkunisquat.lattice.ui.theme.LatticeTheme

class MainActivity : FragmentActivity() {

    private lateinit var biometricGate: BiometricGate

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Permission result handled by the system; if denied, notifications remain suppressed.
    }

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

                // Request notification permission on Android 13+ (API 33)
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

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
