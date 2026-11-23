# Adam's Pixel 9 Pro App

Replaces the Tasker profiles Camera, Car, Keep Screen On, Power and Volume.

> **Heads up:** this is a very personal, niche app. I built it to do exactly what I wanted
> on my own Pixel 9 Pro, replacing my own Tasker setup, so it probably won't suit many
> other people as it is. You're welcome to borrow ideas or code from it, but it isn't a
> general-purpose app and I'm not planning to add features for other setups.

## Install

1. Download `AdamsPixel9ProApp-1.6.apk` from the [Releases](https://github.com/CARRisma1978/Adams-Pixel-App/releases) page on the phone and open it.
2. Allow "Install unknown apps" for whichever app you opened it from.
3. If Play Protect warns that the app was built for an older version of Android, tap
   **More details > Install anyway**. That's expected: the app targets Android 9 on
   purpose so it can toggle Bluetooth and Wi-Fi.
4. Open the app and tap the permissions row at the bottom of the list. Grant each item.
   - If **Usage access** is greyed out, go to Settings > Apps > Adam's Pixel 9 Pro App,
     tap the ⋮ menu, then **Allow restricted settings**, and try again.
   - With Advanced Protection on, Android blocks installing apps from files. Turn it off
     briefly to install or update this app, then turn it back on.
5. Turn the features on. Then turn the matching Tasker profiles off so the two apps
   don't fight.

## Features

| Feature | Trigger | What it does |
|---|---|---|
| Camera quiet | Pixel Camera opens | Do Not Disturb: Priority only. Goes back to how it was when you leave the camera. Uses Usage Access (works with Advanced Protection). |
| Car tag | Tap the NFC tag | Toggles Bluetooth. Use **Write car tag** once to reprogram the tag. |
| Keep screen on | Quick Settings tile | Sets screen timeout to 23:59:59. Back to normal when you tap the tile again or the screen turns off. |
| Wireless charging | On a wireless charger | Do Not Disturb: Priority only. Optionally turns Wi-Fi off (back on afterwards). |
| Volume profiles | Home-screen icon | Normal / Quiet / Silent. The icon and its name show the current profile. Levels default to your Tasker values. Normal and Quiet can also set a ringtone and notification sound. |
| Pi monitor | Open it in the app | Live stats from one or more Raspberry Pis over SSH (password or SSH key; Raspberry Pi OS or LineageOS), plus Reboot and Shut down (EmulationStation is closed properly first on RetroPie). |

Long-press any row on the main menu and drag it to reorder.

## Building from source

Open this folder in Android Studio and press Run, or use `gradlew assembleRelease`.

The release signing key is **not** in this repository. Builds read it from a
`keystore.properties` file in the project root (ignored by git):

```properties
storeFile=app/adams-pixel.keystore
storePassword=...
keyAlias=adams-pixel
keyPassword=...
```

Without that file, the app is signed with the standard Android debug key instead.
That build works, but it won't install as an update over the released APK: you'd have
to uninstall that first. I keep the original key on my PC with a backup elsewhere,
because if it's lost, future builds can't update the installed app.

Key files are in `app/src/main/java/com/adamcarr/pixelapp/`:

- `Core.kt`: settings, feature switches, and the shared Do Not Disturb logic
- `Routines.kt`: Camera, Car, Keep screen on, Wireless charging
- `Volume.kt`: volume profiles and the home-screen icon
- `MainActivity.kt`: the main menu and the settings pages
- `Popups.kt`: the volume pop-up and the NFC tag writer
- `Pi.kt` / `PiScreens.kt`: Raspberry Pi monitor (SSH, stats, reboot/shutdown)
- `app/src/test`: automated tests (UI, drag-to-reorder, and SSH against a test server)

## Licence

MIT. See [LICENSE](LICENSE).
