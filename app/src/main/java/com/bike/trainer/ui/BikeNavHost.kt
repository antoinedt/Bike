package com.bike.trainer.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.bike.trainer.di.ServiceLocator
import com.bike.trainer.ui.connect.ConnectScreen
import com.bike.trainer.ui.connect.SensorScanScreen
import com.bike.trainer.ui.home.HomeScreen
import com.bike.trainer.ui.ride.RideScreen
import com.bike.trainer.ui.settings.SettingsScreen
import com.bike.trainer.ui.setup.SetupScreen
import com.bike.trainer.ui.stats.StatsScreen
import com.bike.trainer.ui.summary.SummaryScreen

object Routes {
    const val SETUP = "setup"
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val CONNECT = "connect"
    const val CONNECT_HR = "connect_hr"
    const val CONNECT_CONTROLLER = "connect_controller"
    const val STATS = "stats"
    const val RIDE = "ride"
    const val SUMMARY = "summary"
}

@Composable
fun BikeNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.SETUP) {
        composable(Routes.SETUP) {
            SetupScreen(
                onConnectTrainer = { navController.navigate(Routes.CONNECT) },
                onConnectHeartRate = { navController.navigate(Routes.CONNECT_HR) },
                onConnectController = { navController.navigate(Routes.CONNECT_CONTROLLER) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onConfirm = { navController.navigate(Routes.HOME) },
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                onBack = { navController.popBackStack() },
                onViewStats = { navController.navigate(Routes.STATS) },
                onStartRide = { navController.navigate(Routes.RIDE) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.CONNECT) {
            ConnectScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.CONNECT_HR) {
            SensorScanScreen(
                title = "Heart-rate monitor",
                subtitle = "Scanning for Bluetooth heart-rate straps…",
                sensor = ServiceLocator.heartRateManager,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.CONNECT_CONTROLLER) {
            SensorScanScreen(
                title = "Gear controller",
                subtitle = "Scanning for a Zwift Click / Play controller…",
                sensor = ServiceLocator.zwiftClickManager,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.STATS) {
            StatsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.RIDE) {
            RideScreen(
                onFinished = {
                    navController.navigate(Routes.SUMMARY) {
                        popUpTo(Routes.HOME)
                    }
                },
                onExit = { navController.popBackStack() },
            )
        }
        composable(Routes.SUMMARY) {
            SummaryScreen(
                onDone = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                },
            )
        }
    }
}
