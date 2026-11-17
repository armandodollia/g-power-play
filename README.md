# g-power-play

Turn a ~$30 **moto g power 2026** (XT2617V, the Tracfone / Straight Talk / Walmart one) into a Wi-Fi-only **emulation handheld**. No root is needed, and the bootloader on these phones is locked anyway.

What you end up with:

- **No carrier activation:** the phone skips the carrier activation wall and runs Wi-Fi only.
- **120 Hz always.** By default it drops as low as 30 Hz.
- **About 65 carrier, ad and Google apps removed,** and the activation and "app pusher" nags blocked.
- **Landscape that follows the controller:** plug in or connect any USB or Bluetooth controller and the phone locks to landscape, lock screen included. Disconnect it and auto-rotate comes back. A Quick Settings tile toggles this by hand.
- **Apps can draw over the camera hole in landscape.** The Argosy frontend's own menus still leave a strip, but games fill the screen.
- **It all survives system updates.** The **Handheld Tweaks** app in this repo puts back whatever an OTA resets, at every boot, with no computer needed.

## 1. First boot: skip activation (Test DPC QR)

On a brand-new or factory-reset phone, this uses Android Enterprise QR provisioning to install Google's **Test DPC**, which gets you past the carrier activation screen.

1. At the very first **Welcome** screen, **tap the same blank spot 6 times**. A QR scanner opens.
2. Connect to Wi-Fi when asked, then scan **[`docs/first-boot/testdpc-qr.png`](docs/first-boot/testdpc-qr.png)**.
3. Test DPC downloads and opens. Pick **Set up as device owner**, or a work profile, then finish setup. You'll see Work and Personal tabs, and the lock screen says "This device belongs to your organization".

![Test DPC QR](docs/first-boot/testdpc-qr.png)

The QR code contains [`docs/first-boot/testdpc.json`](docs/first-boot/testdpc.json): Google's official Test DPC component, download URL and signature checksum, plus "leave all system apps enabled". If Google changes the checksum, regenerate the code with `qrencode -o testdpc-qr.png < docs/first-boot/testdpc.json`.

Already past the Welcome screen? Factory reset first: Settings → System → Reset options. Or use recovery: Power+Vol-Down, then **Recovery mode**, hold Power and tap Vol-Up, then **Wipe data/factory reset**.

## 2. One-time setup from a computer

You need `adb`, `gh`, Python 3 and JDK 17 (`brew install openjdk@17`) on the computer.

1. On the phone, enable **Developer options** (tap Build number 7 times). Then go to **Wireless debugging** → **Pair device with pairing code**.
2. Run:

   ```sh
   scripts/setup.sh <phone-ip> <pairing-port> <pairing-code>
   ```

   The script pairs with the phone and finds the connect port. It installs **Shizuku 13.6+** from GitHub, because the Play Store's 13.5 can't start on boot without root. Then it grants Shizuku `WRITE_SECURE_SETTINGS`, builds and installs **Handheld Tweaks**, and adds the tile.
3. Finish on the phone. The script prints these steps too:
   - Shizuku → **Start via Wireless debugging** → Pairing → Start.
   - Shizuku → Settings → **Start on boot**. It's labelled "(root)", but on 13.6 it also works over wireless debugging with Wi-Fi.
   - Open **Handheld Tweaks** and tap **Grant**.

After that, the phone takes care of itself. The app's status screen shows each tweak and a log.

## 3. Handheld Tweaks (the app)

| Tweak | What it enforces |
|---|---|
| Always 120 Hz | `min_refresh_rate` and `peak_refresh_rate` set to `120.0` |
| Bloat removed | `pm uninstall -k --user 0` for each package in [`docs/debloat-packages.txt`](docs/debloat-packages.txt) |
| Protected bloat blocked | apps that refuse removal (Tracfone account services, Amazon App Manager, Moto PAKS) have notifications and background running set to `ignore` |
| Draw over camera cutout | `am compat enable --no-kill OVERRIDE_LAYOUT_IN_DISPLAY_CUTOUT_MODE` for Argosy |
| Battery whitelist | the app and Argosy are exempt from Moto's background killer |
| Rotation | controller attached → `user_rotation 3` (270°) + `wm fixed-to-user-rotation enabled`; detached → auto-rotate |

The app checks each tweak at boot, whenever Shizuku reconnects, and when you tap "Reapply now", and only changes what has drifted. It never force-stops or kills other apps.

- **Build:** `./gradlew :core:test :app:assembleDebug` (JDK 17).
- **Change what it enforces:** edit `docs/debloat-packages.txt` or `Config` in `core/src/main/kotlin/local/handheld/tweaks/core/Tweaks.kt`, then re-run `scripts/setup.sh`.
- **Design:** see `docs/superpowers/specs/`; the build plan is in `docs/superpowers/plans/`.

## Notes and gotchas

- **Wireless adb over Tailscale or other VPNs:** `adb mdns` finds nothing, so use `scripts/find-adb-port.py <ip>`. The connect port changes on every reboot.
- **Don't use the "Display cutout: Hide" developer option.** It shrinks the whole screen by the cutout width instead of letting apps draw over it.
- **Argosy's menu strip:** Argosy pads its menus away from the cutout in code (`ui/util/LauncherInsets.kt`), so no system setting removes it.
- **Force-stopping Argosy turns off its accessibility service.** Re-enable it with `settings put secure enabled_accessibility_services com.nendo.argosy/com.nendo.argosy.hardware.FocusAccessibilityService`.
- The full story of how this was worked out is in [`docs/JOURNAL.md`](docs/JOURNAL.md).

## Undo

```sh
adb shell pm uninstall local.handheld.tweaks
adb shell wm fixed-to-user-rotation default
adb shell settings put system accelerometer_rotation 1
adb shell settings delete system min_refresh_rate
adb shell cmd package install-existing <package>   # restore any removed app
```

A factory reset undoes everything, Test DPC included.
