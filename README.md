# Lagcoach

Get a notification when the bus you catch is running late, early or cancelled — or when an
earlier bus is so late you can catch it instead.

Save a stop, the routes you use and the window you usually travel in. During that window the
app checks live times and alerts you; afterwards it keeps a record so the Stats tab can show
how often your buses are late and when they really turn up (and, if you add where you get
off, roughly when you arrive).

Live times come from [bustimes.org](https://bustimes.org), which collects operators' open
vehicle-tracking data for Great Britain. Lagcoach isn't affiliated with Stagecoach or
bustimes.org.

## Install

Download the APK from [Releases](https://github.com/vmoore-school/Lagcoach/releases), or
track it in [Obtainium](https://github.com/ImranR98/Obtainium): add the app with
`https://github.com/vmoore-school/Lagcoach` as the source URL. Each release has a single
signed APK, so no extra Obtainium settings are needed.

## Works without Google Play services

There's no server and no push service. Instead the phone wakes itself with an exact alarm just
before each journey window and runs a foreground service that polls bustimes.org (every 60 s
by default) until the window closes. That's why it asks for:

- **Notifications**, for the alerts and the "watching" notification Android requires while
  checking.
- **Unrestricted battery**, so Doze doesn't pause checks with the screen off.
- **Alarms & reminders**, so checking starts on time (granted automatically on Android 13+).
- **Location** (optional), only when you tap "Use my location" to find nearby stops.

On GrapheneOS also leave the **Network** permission on. Nothing runs outside your windows.

## Building

```sh
./gradlew :shared:testDebugUnitTest      # parser + alert logic tests
./gradlew :androidApp:assembleDebug
```

Kotlin Multiplatform + Compose Multiplatform. All UI and logic is in `shared/src/commonMain`;
`androidApp` holds the Android glue (alarms, foreground service, notifications). An iOS target
could reuse the shared code, but iOS doesn't allow background polling, so alerts there would
need a push server.

Pushing to `main` builds a signed APK as a workflow artifact. Pushing a `v*` tag publishes a
GitHub Release. Signing uses the `LAGCOACH_KEYSTORE_B64`, `LAGCOACH_KEYSTORE_PASSWORD`,
`LAGCOACH_KEY_ALIAS` and `LAGCOACH_KEY_PASSWORD` repository secrets.

Figtree font © The Figtree Project Authors, [SIL OFL 1.1](third_party/Figtree-OFL.txt).
