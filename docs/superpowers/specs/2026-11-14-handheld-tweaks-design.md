# Handheld Tweaks — design

Date: 2026-11-14

## Intent

The owner uses a **moto g power 2026 (XT2617V, Tracfone-branded, Android 16, MT6835)** only as an emulation handheld, with a **GameSir X5 Lite** USB-C clip-on controller and the **Argosy** frontend (`com.nendo.argosy`). The bootloader is locked, so root isn't possible.

Over adb from a Mac we applied a set of tweaks. The Motorola system update on 2026-11-14 (patch 2026-06-01 → 2026-08-01) silently reset some of them. The owner wants a small app **on the phone** that keeps these tweaks in place **automatically**, without the Mac. It uses **Shizuku** to get adb-level ("shell") privileges. It also switches rotation based on whether the controller is attached.

## Goals

1. **Reconcile on boot.** After every boot, once Shizuku is available, check each tweak below and reapply only the ones that have drifted. No user action needed.
2. **Controller-driven rotation.**
   - GameSir connected → landscape at **ROTATION_270** (`user_rotation 3`), locked, with the lock screen and all apps forced to it.
   - Disconnected → back to normal **auto-rotate**, with nothing forced.
3. **Quick Settings tile "Landscape lock" (owner's request).** The tile shows **active** when forced landscape is on (`fixed-to-user-rotation` enabled + `user_rotation 3`) and **inactive** otherwise. Tapping it toggles between the controller-connected state and the normal state (same commands as T5). This works whether or not a controller is attached. Per the rotation rule below, a manual toggle holds until the next controller attach/detach or boot. The tile's subtitle shows "Controller connected" when one is. Long-press opens the status screen. If Shizuku isn't available, the tile shows **unavailable**, and tapping it opens the status screen. *(Claude's call on the tap behavior, the subtitle, and the long-press.)*
4. **Status screen.** One screen lists each tweak with its state (applied / drifted / failed) and Shizuku's state. It has a "Reapply now" button.
5. **Survives OTAs.** Same mechanism as goal 1. After an update, the next boot fixes everything.

## Tweaks to enforce (the source of truth)

All of these run as the shell user via Shizuku.

| # | Tweak | Check | Apply |
|---|---|---|---|
| T1 | Always 120 Hz | `settings get system min_refresh_rate` == 120.0 and `peak_refresh_rate` == 120.0 | `settings put system min_refresh_rate 120.0`; `settings put system peak_refresh_rate 120.0` |
| T2 | Debloat | each package in `docs/debloat-packages.txt` (68 entries) absent from `pm list packages --user 0` | `pm uninstall -k --user 0 <pkg>` |
| T3 | Neuter protected bloat | for `com.tracfone.preload.accountservices`, `com.amazon.appmanager`, `com.motorola.paks`, `com.motorola.paks.notification`: `appops get` shows `POST_NOTIFICATION`, `RUN_IN_BACKGROUND`, `RUN_ANY_IN_BACKGROUND` = ignore | `pm revoke <pkg> android.permission.POST_NOTIFICATIONS` (ignore failure); `appops set <pkg> <op> ignore` for each op |
| T4 | Display-cutout override | `dumpsys platform_compat` shows change `OVERRIDE_LAYOUT_IN_DISPLAY_CUTOUT_MODE` (332679525) enabled for each package in the cutout list | `am compat enable OVERRIDE_LAYOUT_IN_DISPLAY_CUTOUT_MODE <pkg>` |
| T5 | Rotation state | matches the controller state (see below) | see below |
| T6 | Keep itself and Argosy alive | own package, `com.nendo.argosy`, and every package in the cutout list are on the deviceidle whitelist (`dumpsys deviceidle whitelist`) | `dumpsys deviceidle whitelist +<pkg>` for each *(Claude's call: covers Argosy and emulators too, so Moto battery management doesn't kill them mid-game)* |

The cutout list starts as `com.nendo.argosy`. Emulator apps added later go in the same list. Argosy's own menus still leave a strip by design; games fill the screen. This tweak exists so the override survives OTAs and covers future emulator apps.

**The debloat has already been run once by hand on this exact build.** All 64 removable entries uninstalled cleanly, the other 4 are the protected ones in T3, and the phone (Wi-Fi, Settings, Play Store, Argosy, Shizuku, Tailscale) works normally afterward. The app's first run is therefore a no-op for T2 on this device, and **no preview/confirm step is needed**. T2 only matters after an OTA or factory reset brings packages back.

T2's list is **hard-coded** (compiled in from `docs/debloat-packages.txt`). Changing it means rebuilding from the Mac. Packages that report `not installed for 0` count as done. Packages that fail with "non-disable" are **not** retried every boot: they're shown as "protected" and handled by T3.

### Rotation (T5)

- **Controller present:** `settings put system accelerometer_rotation 0`; `settings put system user_rotation 3`; `wm fixed-to-user-rotation enabled`.
- **Controller absent:** `wm fixed-to-user-rotation default`; `settings put system accelerometer_rotation 1`.
- **When rotation is touched (owner's decision):** only on controller **attach**, **detach**, a **Quick Settings tile tap**, and **once at service start** (boot or "Reapply now"). In between, the app never re-checks rotation, so a manual portrait lock made from quick settings while unplugged is respected until the next attach/detach/boot. Rotation therefore isn't a "drift" tweak. The status screen shows it as "Controller: connected/not connected · Rotation: landscape-locked / normal", not as applied/drifted.
- **Controller match (owner's decision): any game controller, USB or Bluetooth.** Present = at least one `InputDevice` that is **external** (`isExternal`) and whose sources include `SOURCE_GAMEPAD` or `SOURCE_JOYSTICK`. This excludes the phone's own buttons and plain keyboards and mice. For reference, the GameSir X5 Lite (USB, vendor 0x3537 / product 0x1116) shows up as two input devices, and only one of them has the gamepad source.
- **Detach debounce:** wait ~3 s after the last controller disappears before switching back to normal. This avoids flapping when a USB controller reseats or a Bluetooth controller briefly drops. Attach applies immediately. *(Claude's call. Bluetooth controllers that auto-sleep will still switch the phone back to normal when they sleep; that's accepted.)*
- **Detection:** `InputManager.registerInputDeviceListener` in the foreground service. Changes are applied **immediately** on add/remove, and the state is re-evaluated on service start.

## Constraints

- **No root.** All privileged work goes through **Shizuku** (`moe.shizuku.privileged.api`, already installed from the Play Store). Use the Shizuku API's **UserService** to run code as the shell uid. That code runs commands through `Runtime.exec`/`ProcessBuilder` inside the UserService.
- **Shizuku needs starting after every reboot.** The owner will turn on Shizuku's "start on boot (wireless debugging)". It needs `WRITE_SECURE_SETTINGS` granted to Shizuku over adb once (we'll do that from the Mac) and only works when Wi-Fi is connected. The app must **wait for Shizuku** (`Shizuku.addBinderReceivedListenerSticky`), not fail, when it isn't up yet. The app must also request Shizuku permission once (`Shizuku.requestPermission`) from the status screen.
- **Boot start:** a `BOOT_COMPLETED` (and `LOCKED_BOOT_COMPLETED` if useful) receiver starts a foreground service. The type must be allowed to start from boot on Android 15/16: use `specialUse` with a subtype string, and declare it in the manifest. The service shows a low-importance persistent notification ("Handheld Tweaks active · Controller: connected/not connected").
- **Motorola battery management** ("Smart background" in `com.motorola.batterycare`) may kill background apps. T6 whitelists the app. The app also asks for "unrestricted battery" via the standard intent on first run.
- **Never force-stop or kill other apps.** Force-stopping Argosy turned off its accessibility service once. No tweak may do that.
- **No network access**, no analytics. No `INTERNET` permission.
- **Android 16 (API 36) device.** `minSdk 34`, `targetSdk 36`.
- **Build on the Mac.** The Android SDK is at `~/Library/Android/sdk` (platforms 35/36, build-tools 35/36). There's **no JDK** yet: install `openjdk@17` via Homebrew. Gradle Kotlin DSL. Install with `adb install` over wireless adb (the device's IP is on Tailscale; the connect port changes per reboot).
- Signed with a local debug or release keystore kept in the repo's ignored folder. It's sideloaded only.

## Approaches considered

- **A. Native Kotlin app + Shizuku UserService + foreground service listening for input-device changes.** ✅ *Chosen by the owner.* Event-driven, no polling, needs nothing but Shizuku, has a status UI.
- **B. Termux + Termux:Boot + `rish` shell scripts, polling `dumpsys input` for the controller.** Rejected: needs three extra apps, polling costs battery and adds lag, and it's fragile across updates.
- **Tasker/MacroDroid + Shizuku.** Rejected: Play Store / closed source, and the owner wants their own app.

## Non-goals

- Editing the debloat or cutout lists from the phone (rebuild from the Mac instead).
- Root, bootloader unlock, or anything persistent beyond what shell can do.
- General-purpose automation or per-app rotation.
- Fixing Argosy's menu safe-area padding. It's hard-coded in Argosy (`ui/util/LauncherInsets.kt` pads by `displayCutout`). The owner chose to leave it for now.

## Anti-goals (would count as failure even if it "works")

- Uninstalling anything not on the list, or anything that breaks boot, telephony core, Settings, Play Store/Services, the launcher, Argosy, Shizuku, or Tailscale.
- Force-stopping Argosy or other user apps.
- Visible battery drain from polling or wake locks.
- A noisy notification. One quiet ongoing notification only, plus an alert notification **only** when something fails or Shizuku needs attention.

## Decided by the owner

- Approach A (native app).
- Fully automatic on boot.
- Disconnected = normal auto-rotate. Connected = landscape ROTATION_270 including the lock screen.
- Apps should draw over the camera cutout in landscape (T4).

## Calls made by Claude (correctable)

- Package name `local.handheld.tweaks`, app label "Handheld Tweaks".
- Jetpack Compose for the single status screen.
- Hard-coded lists. Debloat failures that are "non-disable" are shown as protected, not retried each boot.
- Log each reconcile run (timestamp, tweak, result) to a small rolling file in app storage, viewable from the status screen.

## Left to the builder

- Exact Shizuku API version (current 13.x) and the UserService AIDL shape.
- How to parse `appops`/`platform_compat` output robustly.
- Test strategy. Pure parsing and "decide what to apply" logic should be unit-tested off-device. On-device behavior gets verified over adb.
