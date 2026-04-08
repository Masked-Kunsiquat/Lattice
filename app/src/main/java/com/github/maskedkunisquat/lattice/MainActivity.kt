package com.github.maskedkunisquat.lattice

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.maskedkunisquat.lattice.ui.JournalEditorScreen
import com.github.maskedkunisquat.lattice.ui.JournalEditorViewModel
import com.github.maskedkunisquat.lattice.ui.theme.LatticeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as LatticeApplication
        setContent {
            LatticeTheme {
                val editorViewModel: JournalEditorViewModel = viewModel(
                    factory = JournalEditorViewModel.factory(app)
                )
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    JournalEditorScreen(
                        viewModel = editorViewModel,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}
