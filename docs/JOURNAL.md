# Build journal

This is how the phone went from a carrier-locked $30 phone to an emulation handheld, in order, including the dead ends. Everything here was done on 2026-11-14 on a moto g power 2026 (XT2617V, Tracfone build, Android 16, MediaTek MT6835 / Dimensity 6300).

## 0. Skipping activation (Test DPC)

These phones nag for carrier activation on first boot. The trick, from a YouTube video about the Walmart Black Friday unit, is **Android Enterprise QR provisioning**. Six taps on the Welcome screen open a QR scanner, and scanning a code that points at Google's **Test DPC** sets the phone up as a managed device, which skips the activation step.

The video doesn't show the exact data its creator used. We built the QR code from the three values in Google's Test DPC README (component name, download URL, signature checksum) and added `PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED`, so the Play Store, Camera and so on stay. The code and its JSON are in `docs/first-boot/`.

**Side effects:** you get a "Sample Managed Profile" (user 10, owned by TestDPC). The lock screen reads "This device belongs to your organization".

## 1. Wireless adb

Developer options → Wireless debugging → Pair device with pairing code, then `adb pair <ip>:<port> <code>`. The phone was reached over Tailscale (a `100.x` address), where **`adb mdns services` finds nothing**. The *connect* port (different from the pairing port) had to be found by TCP-scanning 30000–50000, which is now `scripts/find-adb-port.py`.

## 2. 120 Hz always

The panel supports 30/60/90/120 Hz, and the default policy idles at 30. Setting both values is enough, and the change survives reboots:

```
settings put system peak_refresh_rate 120.0
settings put system min_refresh_rate 120.0
```

To verify: `dumpsys display` shows `primary=physical: (120.0 120.0)`, and SurfaceFlinger shows `vsyncRate=120.00 Hz`.

## 3. Root? No.

`ro.boot.flash.locked=1`, verified boot is `green`, and there's no OEM-unlock option. Motorola doesn't issue unlock codes for carrier units, and MTK BROM exploits (mtkclient) aren't reliable on this generation of chips. Everything below works without root.

## 4. Activation nags and debloat

The nag notifications came from `com.tracfone.preload.accountservices`. It's marked **non-disable**, so it can't be disabled or uninstalled for the user. Instead it was neutered:

```
pm revoke <pkg> android.permission.POST_NOTIFICATIONS
appops set <pkg> POST_NOTIFICATION ignore
appops set <pkg> RUN_IN_BACKGROUND ignore
appops set <pkg> RUN_ANY_IN_BACKGROUND ignore
```

68 packages were targeted with `pm uninstall -k --user 0`, which can be undone with `cmd package install-existing`. They include:
- Sliide / huub.tiger and Digital Turbine (auto-installs apps)
- Glance lock screen and Facebook
- the AT&T, Cricket and Verizon apps
- Moto extras
- Google apps not needed on a handheld

64 were removed. `com.amazon.appmanager` and `com.motorola.paks` (+`.notification`) are non-disable, so they got the same appops treatment. The list is in `docs/debloat-packages.txt`.

**Gotcha:** the Mac's shell is zsh, where `for p in $LIST` doesn't word-split. The first debloat loop silently did nothing, so loop over a file instead.

## 5. Landscape everywhere, lock screen included

Turning auto-rotate off and setting `user_rotation` isn't enough, because the keyguard stays portrait. What works:

```
settings put system accelerometer_rotation 0
settings put system user_rotation 3      # 270°; 1 is the other landscape
wm fixed-to-user-rotation enabled        # forces every window, keyguard included
```

## 6. Drawing over the camera hole

- **Developer options "Display cutout → Hide"** (`com.android.internal.display.cutout.emulation.noCutout`) is a trap. It shrinks the logical display from 2388 to 2276 px instead of removing the strip. It was reverted.
- **The per-app compat override `OVERRIDE_LAYOUT_IN_DISPLAY_CUTOUT_MODE`** works. Use `am compat enable --no-kill …`: without `--no-kill` it kills the target app. After the override the window spans the full 2388 px.
- **Argosy's menus still stop short of the hole.** `LauncherInsets.kt` unions `displayCutout` into its content padding on purpose. Games launched from it do fill the screen. The owner chose to leave it for now. A toggle upstream (rommapp/argosy-launcher) would fix it.

## 7. The OTA reset

A Motorola update (security patch 2026-06 → 2026-08) reset `min_refresh_rate` and `user_rotation`. The debloat, appops and `fixed-to-user-rotation` survived. That's what led to building the app.

## 8. Handheld Tweaks + Shizuku

- **Shizuku** gives an app adb-level (uid 2000) access through a "UserService".
- **Play Store Shizuku 13.5** can only start on boot with root.
- **GitHub 13.6.0** (same signing key, so it installs over 13.5) adds non-root start on boot for Android 13+ on Wi-Fi. It needs:
  - `pm grant moe.shizuku.privileged.api android.permission.WRITE_SECURE_SETTINGS`
  - Shizuku started once from its own Wireless-debugging flow, so it has its own adb key
  - the "Start on boot (root)" toggle, which is mislabelled
- **The app** is Kotlin and Compose with a pure-JVM `core` module that has unit tests. A boot-started `specialUse` foreground service reconciles the tweaks. `InputManager` listens for any external gamepad or joystick (USB or Bluetooth) and waits 3 s after detach before switching back. A Quick Settings tile toggles landscape.
- **Verified with a reboot after a simulated OTA reset (30 Hz):** Shizuku came up by itself and the app restored 120 Hz about 4 s after boot.
- **Bugs found in review and fixed:**
  - The compat override killed Argosy, now `--no-kill`.
  - A Shizuku bind could hang forever; it now has a 10 s timeout.
  - The first bind after every app update hung. The UserService version is now the install timestamp.
  - Reconcile now runs whenever Shizuku comes back.
  - The tile stays tappable when Shizuku is down.

**Gotchas:**
- Force-stopping Argosy disables its accessibility service.
- `pkill -f shizuku_server` inside `adb shell '…'` kills the adb shell itself, because its own command line matches. Use `kill $(pidof shizuku_server)`.
