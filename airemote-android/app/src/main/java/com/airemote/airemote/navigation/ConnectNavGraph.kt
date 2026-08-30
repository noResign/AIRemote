package com.airemote.airemote.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.airemote.airemote.ui.ConnectScreen

fun NavGraphBuilder.connectNavGraph(navController: NavController) {
    composable(AppRoutes.CONNECT) {
        ConnectScreen(
            onConnectSuccess = {
                navController.navigateToMain()
            }
        )
    }
}
