package com.airemote.airemote.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.airemote.airemote.ui.ChatScreen
import com.airemote.airemote.ui.SessionListScreen
import com.airemote.airemote.ui.SettingsScreen

fun NavGraphBuilder.mainNavGraph(navController: NavController) {
    composable(AppRoutes.MAIN) {
        SessionListScreen(
            onOpenSession = { id -> navController.navigateToConversation(id) },
            onNewSession = { navController.navigateToConversation(null) },
            onSettings = { navController.navigateToSettings() },
        )
    }

    composable(
        route = AppRoutes.CONVERSATION,
        arguments = listOf(
            navArgument("sessionId") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            }
        ),
    ) { entry ->
        val sessionId = entry.arguments?.getString("sessionId")
        ChatScreen(
            sessionId = sessionId,
            onBack = { navController.popBackStack() },
        )
    }

    composable(AppRoutes.SETTING) {
        SettingsScreen(
            onBack = { navController.popBackStack() },
            onReconnect = { navController.navigateToConnect() },
        )
    }
}
