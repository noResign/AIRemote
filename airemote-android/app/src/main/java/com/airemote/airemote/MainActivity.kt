package com.airemote.airemote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.airemote.airemote.navigation.AppNavHost
import com.airemote.airemote.ui.theme.AIRemoteTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AIRemoteTheme {
                AppNavHost()
            }
        }
    }
}
