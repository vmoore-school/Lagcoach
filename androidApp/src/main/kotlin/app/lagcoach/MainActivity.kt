package app.lagcoach

import android.Manifest
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import app.lagcoach.ui.App

class MainActivity : ComponentActivity() {
    private val platform get() = (application as LagcoachApp).platform
    var askedNotifications = false
        private set
    private var locationCallback: ((Boolean) -> Unit)? = null

    private val notificationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        platform.refreshHealth()
    }
    private val locationLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        locationCallback?.invoke(r.values.any { it })
        locationCallback = null
    }

    fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            askedNotifications = true
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun requestLocation(onResult: (Boolean) -> Unit) {
        locationCallback = onResult
        locationLauncher.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Every screen has a navy header, so status bar icons are always light.
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        platform.activity = this
        handleOpen(intent)
        setContent { App() }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleOpen(intent)
    }

    private fun handleOpen(intent: android.content.Intent?) {
        intent?.getStringExtra(Notifier.EXTRA_OPEN)?.let { AppGraph.openRequest.value = it }
    }

    override fun onResume() {
        super.onResume()
        platform.activity = this
        platform.refreshHealth()
        AppGraph.repo.reload()
    }

    override fun onDestroy() {
        if (platform.activity === this) platform.activity = null
        super.onDestroy()
    }
}
