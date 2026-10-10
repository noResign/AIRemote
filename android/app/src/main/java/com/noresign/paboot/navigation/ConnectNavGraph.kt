package com.noresign.paboot.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.noresign.paboot.ui.ConnectScreen

fun NavGraphBuilder.connectNavGraph(navController: NavController) {
    composable(AppRoutes.CONNECT) {
        ConnectScreen(
            onConnectSuccess = {
                navController.navigateToMain()
            }
        )
    }
}
