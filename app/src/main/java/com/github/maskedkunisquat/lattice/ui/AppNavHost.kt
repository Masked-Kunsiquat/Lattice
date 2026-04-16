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
import com.github.maskedkunisquat.lattice.BuildConfig
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
                            ?.any { it.route == dest.route || it.route?.startsWith(dest.route + "/") == true } == true,
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

            composable("history") {
                val vm: JournalHistoryViewModel = viewModel(
                    factory = JournalHistoryViewModel.factory(app),
                )
                val searchVm: SearchHistoryViewModel = viewModel(
                    factory = SearchHistoryViewModel.factory(app),
                )
                Box(Modifier.fillMaxSize().testTag("screen:history")) {
                    JournalHistoryScreen(
                        viewModel = vm,
                        searchViewModel = searchVm,
                        onOpenEntry = { entryId ->
                            navController.navigate("history/$entryId")
                        },
                    )
                }
            }

            composable("history/{entryId}") { backStackEntry ->
                val entryId = backStackEntry.arguments?.getString("entryId")
                    ?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
                    ?: return@composable
                val vm: EntryDetailViewModel = viewModel(
                    factory = EntryDetailViewModel.factory(app, entryId),
                )
                Box(Modifier.fillMaxSize().testTag("screen:entry-detail")) {
                    EntryDetailScreen(
                        viewModel = vm,
                        onBack = { navController.popBackStack() },
                    )
                }
            }

            composable("settings") {
                val vm: SettingsViewModel = viewModel(
                    factory = SettingsViewModel.factory(app),
                )
                Box(Modifier.fillMaxSize().testTag("screen:settings")) {
                    SettingsScreen(
                        viewModel = vm,
                        onNavigateToAudit = { navController.navigate("settings/audit") },
                        onNavigateToActivities = { navController.navigate("settings/activities") },
                        onNavigateToDebugSeed = { navController.navigate("settings/debug/seed") },
                    )
                }
            }

            composable("settings/audit") {
                val vm: AuditTrailViewModel = viewModel(
                    factory = AuditTrailViewModel.factory(app),
                )
                Box(Modifier.fillMaxSize().testTag("screen:audit")) {
                    AuditTrailScreen(viewModel = vm)
                }
            }

            composable("settings/activities") {
                val vm: ActivityHierarchyViewModel = viewModel(
                    factory = ActivityHierarchyViewModel.factory(app),
                )
                Box(Modifier.fillMaxSize().testTag("screen:activities")) {
                    ActivityHierarchyScreen(viewModel = vm)
                }
            }

            if (BuildConfig.DEBUG) {
                composable("settings/debug/seed") {
                    val vm: DebugSeedViewModel = viewModel(
                        factory = DebugSeedViewModel.factory(app),
                    )
                    Box(Modifier.fillMaxSize().testTag("screen:debug-seed")) {
                        DebugSeedScreen(viewModel = vm)
                    }
                }
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
