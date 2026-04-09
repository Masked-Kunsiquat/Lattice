package com.github.maskedkunisquat.lattice.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.github.maskedkunisquat.lattice.LatticeApplication

private sealed class BottomNavDest(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    object Editor   : BottomNavDest("editor",   "Journal",  Icons.Filled.Edit)
    object History  : BottomNavDest("history",  "History",  Icons.Filled.History)
    object Settings : BottomNavDest("settings", "Settings", Icons.Filled.Settings)
}

private val bottomNavDests = listOf(
    BottomNavDest.Editor,
    BottomNavDest.History,
    BottomNavDest.Settings,
)

@Composable
fun AppNavHost(app: LatticeApplication) {
    val navController = rememberNavController()

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination
                bottomNavDests.forEach { dest ->
                    NavigationBarItem(
                        selected = currentDestination
                            ?.hierarchy
                            ?.any { it.route == dest.route } == true,
                        onClick = {
                            navController.navigate(dest.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "editor",
            modifier = Modifier.padding(innerPadding),
        ) {
            composable("editor") {
                val vm: JournalEditorViewModel = viewModel(
                    factory = JournalEditorViewModel.factory(app),
                )
                Box(Modifier.fillMaxSize().testTag("screen:editor")) {
                    JournalEditorScreen(viewModel = vm)
                }
            }

            // TODO(6.7): replace with JournalHistoryScreen
            composable("history") {
                PlaceholderScreen("History", Modifier.testTag("screen:history"))
            }

            // TODO(6.7): load entry by id, open editor in edit mode
            composable("history/{entryId}") {
                val vm: JournalEditorViewModel = viewModel(
                    factory = JournalEditorViewModel.factory(app),
                )
                Box(Modifier.fillMaxSize().testTag("screen:editor")) {
                    JournalEditorScreen(viewModel = vm)
                }
            }

            // TODO(6.5): replace with SettingsScreen
            composable("settings") {
                PlaceholderScreen("Settings", Modifier.testTag("screen:settings"))
            }

            // TODO(6.6): replace with AuditTrailScreen
            composable("settings/audit") {
                PlaceholderScreen("Audit Trail", Modifier.testTag("screen:audit"))
            }

            // TODO(6.5): replace with ActivityHierarchyScreen
            composable("settings/activities") {
                PlaceholderScreen("Behavioral Activation", Modifier.testTag("screen:activities"))
            }
        }
    }
}

@Composable
private fun PlaceholderScreen(title: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
    }
}
