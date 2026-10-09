package com.xern.xrnhabit.app.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.xern.xrnhabit.app.XrnhabitApp
import com.xern.xrnhabit.app.block.BlockerForegroundService
import com.xern.xrnhabit.app.ui.theme.XrnhabitTheme

class MainActivity : ComponentActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as XrnhabitApp).container
        val store = container.store
        val hardcoreGuard = container.hardcoreGuard
        // The process may have come up in the background, where Android refused this start;
        // being on screen is always an allowed moment, so retry here.
        BlockerForegroundService.start(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            XrnhabitTheme {
                val world by store.world.collectAsStateWithLifecycle()
                val nav = rememberNavController()
                val current by nav.currentBackStackEntryAsState()
                val route = current?.destination?.route

                Scaffold(
                    bottomBar = {
                        // Hide the tab bar on full-screen sub-pages (create / detail).
                        if (route == null || route in Tabs.map { it.route }) {
                            NavigationBar {
                                Tabs.forEach { tab ->
                                    val selected = current?.destination?.hierarchy?.any { it.route == tab.route } == true
                                    NavigationBarItem(
                                        selected = selected,
                                        onClick = {
                                            nav.navigate(tab.route) {
                                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        },
                                        icon = { Icon(tab.icon, contentDescription = null) },
                                        label = { Text(tab.label) },
                                    )
                                }
                            }
                        }
                    },
                ) { padding ->
                    NavHost(
                        navController = nav,
                        startDestination = Tab.Blocks.route,
                        modifier = Modifier.padding(padding),
                    ) {
                        composable(Tab.Blocks.route) {
                            BlocksScreen(
                                world,
                                store,
                                onOpenBlock = { nav.navigate("block/$it") },
                                onCreateBlock = { nav.navigate("createBlock") },
                                onOpenSettings = { nav.navigate(Tab.Settings.route) },
                            )
                        }
                        composable(Tab.Insights.route) { InsightsScreen(world, container.websites, container.siteUsage) }
                        composable(Tab.Settings.route) {
                            SettingsScreen(world, store, hardcoreGuard, container.websites, container.loop)
                        }
                        composable("createBlock") {
                            CreateBlockScreen(
                                store = store,
                                websites = container.websites,
                                loop = container.loop,
                                onDone = { nav.popBackStack() },
                            )
                        }
                        composable("block/{id}") { entry ->
                            BlockDetailScreen(
                                world = world,
                                store = store,
                                blockId = entry.arguments?.getString("id").orEmpty(),
                                onBack = { nav.popBackStack() },
                                guard = hardcoreGuard,
                                loop = container.loop,
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (application as XrnhabitApp).container.loop.sync()
    }
}

sealed class Tab(val route: String, val label: String, val icon: ImageVector) {
    data object Blocks : Tab("blocks", "Blocks", Icons.AutoMirrored.Filled.ListAlt)
    data object Insights : Tab("insights", "Insights", Icons.Filled.Insights)
    data object Settings : Tab("settings", "Settings", Icons.Filled.Tune)
}

private val Tabs = listOf(Tab.Blocks, Tab.Insights, Tab.Settings)
