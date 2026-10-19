# Install Watchdog

Taps **Install / Update** in the Android package installer for the apps on your allowlist
(by default: Kiosk Satellite), so wall tablets and kiosks can update without anyone walking up to them.

Nothing else: no internet access, no data collection, no ADB, no Shizuku, no root.
Works on stock Android and LineageOS, **Android 7.0 and newer**.

The story behind it: https://rutg3r.com/installwatchdog-auto-install-android-updates-home-assistant/

> **Disclaimer.** Install Watchdog confirms app installs on your behalf. Use it only on devices you
> manage yourself, and only allowlist apps you trust. It is provided as is, without warranty of any
> kind (see [LICENSE](LICENSE)). It is not available on Google Play and is not affiliated with
> Google or with the apps you allowlist.

## Download
Get the APK from the [Releases](../../releases) page. Each release lists the **SHA-256 checksum**
of the APK. Check it before installing (see [Verify the download](#verify-the-download)).

## How it works
- An Android **accessibility service** watches only the system installer
  (`com.android.packageinstaller` on LineageOS/AOSP, `com.google.android.packageinstaller` on Google builds).
- When the Install/Update screen shows an app whose name is **exactly** on your allowlist, it taps the button.
  It retries while Android briefly keeps the button disabled, and closes the "Done" screen if one appears.
- Apps that are not on the allowlist are never touched. There is no wildcard; every app must be named.
- After an allowlisted app is updated, it **re-opens** that app (Kiosk Satellite is closed by its own update).
- It turns the screen on if it's off when an install screen appears.
- The setup screen shows whether the service is running, the allowed apps and the **last action** it took.

Android itself still protects updates: an update only installs if it is signed with the same key as the
installed app, so a look-alike APK cannot replace your existing Kiosk Satellite.

## Setup on a tablet
1. Install the APK. Android asks you to allow your browser or file manager to install apps.
2. Open **Install Watchdog** and read the explanation, then tap **I understand**.
3. **Android 13+ only**: tap *App info* → ⋮ menu → **Allow restricted settings**.
   Without this, Android keeps the accessibility switch greyed out for sideloaded apps.
4. Tap **Enable accessibility service** → Install Watchdog → On.
5. Optionally set a **Device name**, check the **Allowlist** and tap **Save**.

The app that downloads the update (for example Kiosk Satellite) needs its own
"Install unknown apps" permission, otherwise it can't open the installer at all.

**Screen lock:** the installer can't appear over a PIN or pattern lock. Use "None" or "Swipe" on kiosk tablets.

**Language:** buttons are matched by view ID first, then by text. English, Dutch and German are built in
(`INSTALL_WORDS` in `WatchdogService.kt`).

## Surviving reboots
Android starts an enabled accessibility service again by itself after every boot. Nothing else is needed.
Don't use "Force stop" on the app: on some ROMs that switches the accessibility service off.

## Verify the download
Compare the checksum with the one listed on the release page.

Windows (PowerShell):
```
Get-FileHash .\InstallWatchdog-2.1.0-release.apk -Algorithm SHA256
```
macOS / Linux:
```
shasum -a 256 InstallWatchdog-2.1.0-release.apk
```

## Build it yourself
Requires Android Studio (Koala or newer, JDK 17). No third-party libraries.

1. Optional: change the default allowlist in `watchdog.properties`.
2. Open the project folder in Android Studio (File → Open) and let Gradle sync.
3. Build → Clean Project, then Build → Build App Bundle(s) / APK(s) → **Build APK(s)**.

Without a signing key (below), the release build is signed with your local debug key.
That's fine for your own devices, but don't publish it.

## Signing key (for publishing releases)
Android only accepts an update that is signed with the **same key** as the installed app.
Lose the key and you can never publish an update that installs over your existing APK.

1. Create the key once: **Build → Generate Signed App Bundle / APK → APK → Create new…**
   Save it **outside** the project, for example `../keys/installwatchdog-release.jks`.
   Use alias `installwatchdog`, a validity of 50 years, and strong passwords.
   You can cancel the wizard after the key is created.
2. Copy `keystore.properties.example` to `keystore.properties` and fill in the path and passwords.
   `keystore.properties`, `*.jks` and `*.keystore` are in `.gitignore`: never commit them.
3. **Back up** the `.jks` file and the passwords somewhere safe (password manager, NAS, USB stick).
4. Build as above. The APK in `app/build/outputs/apk/release/` is now signed with your key.

Moving existing devices from the debug key to your release key requires uninstalling
the old version once, then installing the newly signed APK.

## Publishing a release
1. Build `InstallWatchdog-<version>-release.apk` with your signing key.
2. Calculate its SHA-256 (see above).
3. On GitHub: Releases → Draft a new release → tag `v<version>`, attach the APK,
   and put the SHA-256 in the release notes.

## Version history
- **2.1.0**: first public release.

## License
MIT, see [LICENSE](LICENSE).
