package com.github.maskedkunisquat.lattice

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.github.maskedkunisquat.lattice.ui.AppNavHost
import com.github.maskedkunisquat.lattice.ui.theme.LatticeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as LatticeApplication
        setContent {
            LatticeTheme {
                AppNavHost(app = app)
            }
        }
    }
}
