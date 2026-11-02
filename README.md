# VitalsBridge

FOSS, local-only bridge that gets **heart rate and step data from any Wear OS
watch into Health Connect** — without Samsung Health, without a Samsung
account, and without any cloud involvement.

```
Wear OS watch                         Android phone
┌──────────────────────────┐          ┌─────────────────────────────┐
│ BioActive sensors        │          │                             │
│   └─ Health Services     │  Message │  VitalsBridge Phone         │
│      (PassiveMonitoring) ├─────────►│    └─ HealthConnectWriter   │
│   └─ VitalsBridge Wear   │ Wear OS  │         └─ Health Connect   │
│      Room buffer         │ DataLayer│              └─ any reader  │
└──────────────────────────┘          └─────────────────────────────┘
```

## Why this exists

On a Samsung Galaxy Watch, the only "official" path from watch sensors to
Health Connect goes through the Samsung Health app on the phone — which
requires accepting Samsung's privacy terms and is the sole source of your
vitals data. Health Connect does not run on Wear OS, so there is no way for a
phone app to talk to the watch directly.

But Wear OS itself ships **Health Services**, a Google API that exposes heart
rate and steps from the watch's sensors to any app. VitalsBridge uses it:

- **wear/** — a watch app that registers a passive listener for
  `HEART_RATE_BPM` and `STEPS_DAILY`, buffers samples in a local Room
  database, and pushes them to the phone over the standard Wear OS data layer.
  Auto-sync runs every 15 minutes via WorkManager and opportunistically on
  every incoming sample; batches are only marked synced after the phone
  acknowledges them, so nothing is lost when the phone is away. Registration
  re-arms itself on boot and app update.
- **phone/** — receives the batches and writes `HeartRateRecord` /
  `StepsRecord` into Health Connect, from where any reader (OpenVitals,
  Gadgetbridge dashboard, your own app) can display them.

## What it does *not* provide

Health Services only exposes heart rate and movement metrics. HRV, SpO2, skin
temperature, ECG, respiratory rate and sleep staging are Samsung-proprietary
sensor APIs and are **not** available through this bridge. For sleep,
[Sleep as Android](https://urbandroid.org/) writes sleep sessions to Health
Connect locally and pairs well with this setup.

## Privacy properties

- Neither app declares `android.permission.INTERNET`
- The watch→phone hop is Bluetooth (Wear OS data layer); everything else is
  on-device
- No Samsung account, no Samsung Health required anywhere in the chain
- Code is small (~600 lines across both apps) — auditable in one sitting

Generated with AI assistance (OpenCode / GLM); reviewed and tested on a
Galaxy Watch7 + Galaxy S24 (Android 16).

## Requirements

- Watch: Wear OS 4+ (Health Services with passive monitoring)
- Phone: Android 14+ with Health Connect built in, Google Play Services
  (for the Wear data layer)

## Build

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew :wear:assembleDebug :phone:assembleDebug
```

## Install & set up

1. `adb install -r phone/build/outputs/apk/debug/phone-debug.apk`
2. Open **VitalsBridge Phone** → *Grant Health Connect perms* → Allow all
3. Install the watch APK (via `adb install` over wireless debugging, or
   `adb install` with the watch plugged into a USB-C hub)
4. Open **VitalsBridge** on the watch → *Enable tracking* → *Sync to phone*
   (after this, sync is automatic)

Then open your Health Connect reader — e.g.
[OpenVitals](https://play.google.com/store/apps/details?id=tech.mmarca.openvitals)
— and grant it read access if it hasn't already.

## Troubleshooting

- **No HR samples accumulating?** Continuous heart rate pauses while the
  watch is charging, and Samsung watches may throttle background sampling.
  Wear the watch off the charger for a few minutes.
- **Permission dialog flashes and closes** on the phone: your manifest must
  declare the Android 14+ rationale intent (`VIEW_PERMISSION_USAGE` +
  `HEALTH_PERMISSIONS` category) — see `phone/src/main/AndroidManifest.xml`.

## License

GPL-3.0 — see [LICENSE](LICENSE).
