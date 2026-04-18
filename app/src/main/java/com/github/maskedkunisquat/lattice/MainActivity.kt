package com.github.maskedkunisquat.lattice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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

                // Skip the gate on devices with no lock screen — better than a permanent block.
                if (!biometricGate.canAuthenticate() || isUnlocked) {
                    // Request notification permission after unlock on Android 13+ (API 33).
                    // Guards: only launches if not already granted, and only once per install
                    // (persisted via SharedPreferences) to avoid re-prompting after denial.
                    LaunchedEffect(Unit) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            val granted = ContextCompat.checkSelfPermission(
                                this@MainActivity, Manifest.permission.POST_NOTIFICATIONS
                            ) == PackageManager.PERMISSION_GRANTED
                            val prefs = getSharedPreferences("lattice_perm", Context.MODE_PRIVATE)
                            val alreadyAsked = prefs.getBoolean("asked_once_notifications", false)
                            if (!granted && !alreadyAsked) {
                                prefs.edit().putBoolean("asked_once_notifications", true).apply()
                                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                    }
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
