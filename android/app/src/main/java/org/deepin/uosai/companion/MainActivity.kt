package org.deepin.uosai.companion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.deepin.uosai.companion.app.LaunchConnectionPolicy
import org.deepin.uosai.companion.app.CompanionViewModel
import org.deepin.uosai.companion.ui.CompanionApp

class MainActivity : ComponentActivity() {
    private var incomingPairingUri by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingPairingUri = intent?.dataString
        setContent {
            val model: CompanionViewModel = viewModel()
            val state = model.state.collectAsStateWithLifecycle().value
            val pairingUri = incomingPairingUri
            LaunchedEffect(pairingUri) {
                LaunchConnectionPolicy(model::reconnect, model::pairFromDeepLink).handle(pairingUri)
            }
            MaterialTheme {
                Surface {
                    CompanionApp(state = state, model = model)
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingPairingUri = intent.dataString
    }
}
