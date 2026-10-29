package com.kyrus.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kyrus.assistant.ui.KyrusNav
import com.kyrus.assistant.ui.theme.KyrusTheme
import com.kyrus.assistant.ui.AppViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KyrusTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val vm: AppViewModel = viewModel()
                    val ready by vm.setupComplete.collectAsState()
                    KyrusNav(setupComplete = ready, vm = vm)
                }
            }
        }
    }
}
