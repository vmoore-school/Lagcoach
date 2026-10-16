package app.lagcoach

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class AndroidPlatform(private val app: Context) : Platform {
    /** Set by MainActivity while it's alive; used for permission prompts. */
    var activity: MainActivity? = null

    private val _healthTick = MutableStateFlow(0)
    override val healthTick: StateFlow<Int> = _healthTick
    fun refreshHealth() { _healthTick.value++ }

    override val appVersion: String =
        app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "?"

    override fun healthChecks(): List<HealthItem> {
        val items = mutableListOf<HealthItem>()
        val notif = NotificationManagerCompat.from(app).areNotificationsEnabled()
        items += HealthItem("notifications", "Notifications allowed",
            if (notif) "Alerts will show on your lock screen." else "Without this you won't hear about late buses.",
            notif, "Allow notifications", problem = "Notifications are off")

        val pm = app.getSystemService(PowerManager::class.java)
        val battery = pm.isIgnoringBatteryOptimizations(app.packageName)
        items += HealthItem("battery", "Battery: unrestricted",
            if (battery) "Android won't pause checks while the screen is off."
            else "Needed so checks keep running with the screen off. It only works during your journey windows.",
            battery, "Allow", problem = "Battery saving may pause checks")

        val am = app.getSystemService(AlarmManager::class.java)
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        items += HealthItem("alarms", "Alarms & reminders",
            if (exact) "Checking starts on time." else "Without this, checking may start late.",
            exact, "Allow", problem = "Exact alarms are off")

        val cm = app.getSystemService(ConnectivityManager::class.java)
        val bgData = cm.restrictBackgroundStatus != ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        items += HealthItem("data", "Background data",
            if (bgData) "Live times can load while the app is closed. On GrapheneOS also keep the Network permission on."
            else "Data Saver is blocking Lagcoach in the background.",
            bgData, "Open settings", problem = "Background data is blocked")

        val loc = hasLocation()
        items += HealthItem("location", "Location (optional)",
            if (loc) "Used only when you tap \"Use my location\"." else "Only needed to find stops near you. Search works without it.",
            loc, "Allow", required = false)
        return items
    }

    private fun hasLocation() =
        ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("BatteryLife")
    override fun fixHealth(id: String) {
        val act = activity
        when (id) {
            "notifications" -> if (Build.VERSION.SDK_INT >= 33 && act != null &&
                !act.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) && !act.askedNotifications
            ) {
                act.requestNotifications()
            } else {
                open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName))
            }
            "battery" -> open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${app.packageName}")))
            "alarms" -> if (Build.VERSION.SDK_INT >= 31) {
                open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${app.packageName}")))
            }
            "data" -> open(Intent(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS, Uri.parse("package:${app.packageName}")))
            "location" -> act?.requestLocation { refreshHealth() }
        }
    }

    private fun open(i: Intent) {
        try {
            (activity ?: app).startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            app.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    override fun testNotification() = Notifier.test(app)

    override fun openUrl(url: String) = open(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    override fun watchesChanged() = Scheduler.reschedule(app)

    @SuppressLint("MissingPermission")
    override suspend fun currentLocation(): GeoResult {
        if (!hasLocation()) {
            val act = activity ?: return GeoResult.Denied
            val granted = suspendCancellableCoroutine { cont -> act.requestLocation { cont.resume(it) } }
            refreshHealth()
            if (!granted) return GeoResult.Denied
        }
        val lm = app.getSystemService(LocationManager::class.java)
        if (!LocationManagerCompat.isLocationEnabled(lm)) return GeoResult.Unavailable
        val providers = lm.getProviders(true)
        // A recent fix is good enough for "stops near me".
        providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < 2 * 60 * 1000 }
            .minByOrNull { it.accuracy }
            ?.let { return GeoResult.Ok(it.latitude, it.longitude) }
        // GrapheneOS has no Google network location by default, so prefer fused/GPS.
        val order = listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { it in providers }
        for (p in order) {
            val loc = withTimeoutOrNull(25_000) { fix(lm, p) }
            if (loc != null) return GeoResult.Ok(loc.latitude, loc.longitude)
        }
        return GeoResult.Unavailable
    }

    @SuppressLint("MissingPermission")
    private suspend fun fix(lm: LocationManager, provider: String): Location? = suspendCancellableCoroutine { cont ->
        val signal = CancellationSignal()
        cont.invokeOnCancellation { signal.cancel() }
        LocationManagerCompat.getCurrentLocation(lm, provider, signal, ContextCompat.getMainExecutor(app)) { cont.resume(it) }
    }
}
