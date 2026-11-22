package be.meuse.engis

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.meuse.engis.ui.MeuseScreen
import be.meuse.engis.ui.MeuseTheme
import be.meuse.engis.ui.MeuseViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MeuseViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MeuseTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                LifecycleResumeEffect(Unit) {
                    viewModel.startAutoRefresh()
                    onPauseOrDispose { viewModel.stopAutoRefresh() }
                }
                MeuseScreen(
                    state = state,
                    onRefresh = viewModel::refresh,
                    onReference = viewModel::setReference,
                    onThresholds = viewModel::setThresholds,
                )
            }
        }
    }
}
