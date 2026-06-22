package com.bike.trainer

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.bike.trainer.di.ServiceLocator
import com.bike.trainer.strava.StravaConfig
import com.bike.trainer.ui.BikeNavHost
import com.bike.trainer.ui.theme.BikeTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BikeTheme {
                BikeNavHost()
            }
        }
        handleStravaRedirect(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleStravaRedirect(intent)
    }

    /** Completes the OAuth flow when Strava redirects to bike://strava-auth?code=... */
    private fun handleStravaRedirect(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "bike" || data.host != "strava-auth") return

        val error = data.getQueryParameter("error")
        if (error != null) {
            Toast.makeText(this, "Strava authorization denied", Toast.LENGTH_LONG).show()
            return
        }
        val code = data.getQueryParameter("code") ?: return
        if (!StravaConfig.isConfigured) {
            Toast.makeText(this, "Strava credentials not configured in this build", Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            val ok = ServiceLocator.stravaRepository.exchangeAuthorizationCode(code)
            Toast.makeText(
                this@MainActivity,
                if (ok) "Connected to Strava" else "Could not connect to Strava",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}
