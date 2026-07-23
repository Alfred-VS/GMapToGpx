package ch.tscsoft.gmaptogpx

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.tscsoft.gmaptogpx.ui.screens.MainScreen
import ch.tscsoft.gmaptogpx.ui.theme.GMapToGpxTheme
import ch.tscsoft.gmaptogpx.ui.topbar.MainTopAppBar
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        setContent {
            val viewModel: MapViewModel = viewModel()
            val context = LocalContext.current

            // Initialize preferences
            LaunchedEffect(Unit) {
                viewModel.initPrefs(context)
            }
            
            // Handle incoming intent
            LaunchedEffect(intent) {
                handleIntent(intent, viewModel)
            }

            GMapToGpxTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        MainTopAppBar(viewModel)
                    }
                ) { innerPadding ->
                    MainScreen(viewModel, modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun handleIntent(intent: Intent?, viewModel: MapViewModel) {
        val context = this
        when {
            intent?.action == Intent.ACTION_SEND && intent.type == "text/plain" -> {
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
                    viewModel.processSharedText(it, context)
                }
            }
            (intent?.action == Intent.ACTION_VIEW || intent?.action == Intent.ACTION_SEND) && 
            (intent.type?.contains("gpx") == true || intent.data?.path?.endsWith(".gpx") == true || intent.type == "application/octet-stream") -> {
                val uri = if (intent.action == Intent.ACTION_SEND) {
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                    }
                } else {
                    intent.data
                }
                uri?.let { viewModel.processSharedUri(it, context) }
            }
        }
    }
}
