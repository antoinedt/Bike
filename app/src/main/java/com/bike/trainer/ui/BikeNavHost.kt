package com.bike.trainer.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.bike.trainer.ui.connect.ConnectScreen
import com.bike.trainer.ui.home.HomeScreen
import com.bike.trainer.ui.ride.RideScreen
import com.bike.trainer.ui.summary.SummaryScreen

object Routes {
    const val HOME = "home"
    const val CONNECT = "connect"
    const val RIDE = "ride"
    const val SUMMARY = "summary"
}

@Composable
fun BikeNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onConnectTrainer = { navController.navigate(Routes.CONNECT) },
                onStartRide = { navController.navigate(Routes.RIDE) },
            )
        }
        composable(Routes.CONNECT) {
            ConnectScreen(onBack = { navController.popBackStack() })
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
