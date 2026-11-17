# Handheld Tweaks Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A sideloaded Android app that uses Shizuku to keep the owner's moto g power tweaks applied automatically, and forces landscape while any game controller is attached.

**Architecture:** Pure Kotlin logic in a separate Gradle module, `core` (JVM, unit-tested), handles parsing shell output, deciding which commands to run, and detecting controllers and debouncing their state. The `app` module wires that logic to Android: a Shizuku UserService runs shell commands as uid 2000, a `specialUse` foreground service starts at boot, reconciles tweaks and listens for input devices, and the app also has a Quick Settings tile and a Compose status screen.

**Tech Stack:** Kotlin 2.x, AGP 8.x, Gradle Kotlin DSL, Jetpack Compose (Material 3), Shizuku API `dev.rikka.shizuku:api` + `provider` 13.1.5, JUnit 4, JDK 17.

**Spec:** `docs/superpowers/specs/2026-11-14-handheld-tweaks-design.md`

## Global Constraints

- applicationId / namespace `local.handheld.tweaks`; label "Handheld Tweaks".
- `minSdk 34`, `targetSdk 36`, `compileSdk 36`. No `INTERNET` permission.
- All privileged work goes through the Shizuku UserService. Never call `am force-stop`, `kill` or `pm clear` on any package.
- 120 Hz values: the string `120.0` for both `min_refresh_rate` and `peak_refresh_rate` (system namespace).
- Landscape state: `accelerometer_rotation 0`, `user_rotation 3`, `wm fixed-to-user-rotation enabled`. Normal state: `wm fixed-to-user-rotation default`, `accelerometer_rotation 1`.
- Protected packages (T3): `com.tracfone.preload.accountservices`, `com.amazon.appmanager`, `com.motorola.paks`, `com.motorola.paks.notification`. Ops: `POST_NOTIFICATION`, `RUN_IN_BACKGROUND`, `RUN_ANY_IN_BACKGROUND` → `ignore`.
- Cutout list: `com.nendo.argosy`. Compat change name `OVERRIDE_LAYOUT_IN_DISPLAY_CUTOUT_MODE`.
- Deviceidle whitelist: own package + `com.nendo.argosy` + the cutout list.
- Debloat list: `docs/debloat-packages.txt`, copied into `core` as a resource at build time. `pm uninstall` output `Failure [not installed for 0]` = done; output containing `non-disable` = protected (don't retry each boot).
- Controller = an external `InputDevice` whose sources include `SOURCE_GAMEPAD` (0x401) or `SOURCE_JOYSTICK` (0x01000010). Detach debounce 3000 ms; attach is immediate.
- Rotation is applied only on attach, detach, tile tap, and service start. It's never part of drift checks.
- Notifications: one low-importance ongoing notification ("Handheld Tweaks active · Controller: connected/not connected"), plus an alert only on failure or when Shizuku needs attention.
- Device for on-device checks: `adb -s <phone-ip>:<port>`. Find the port by TCP-scanning 30000–50000 (`scripts/find-adb-port.py <phone-ip>`). It changes every reboot.

## Review Focus

- **Shizuku not running at boot** (Wi-Fi not up yet, or the owner hasn't enabled auto-start): the service must stay alive, show "Waiting for Shizuku", and reconcile once the binder arrives. It must not crash or exit. → test in Task 5 (on-device: start the service with Shizuku stopped, then start Shizuku).
- **Shizuku running but permission not granted to the app**: the tile shows unavailable, the status screen shows a "Grant" button, and nothing throws. → Task 4 `ShizukuGateway.state()` returns `NO_PERMISSION`. Checked in Task 7.
- **The GameSir registers two input devices, only one a gamepad; unplugging removes both a few ms apart**: exactly one switch to normal, 3 s after the last one goes. → `ControllerTrackerTest.twoDevicesRemovedBackToBack_singleDetachAfterDebounce` in Task 3.
- **Re-attach within the debounce window** (cable wiggle): no switch back to normal. → `ControllerTrackerTest.reattachWithinDebounce_noDetach` in Task 3.
- **Unexpected shell output** (OEM format changes after an OTA, empty output, non-zero exit): that tweak is reported `FAILED` with the raw output logged, and the other tweaks still run. → `ReconcilerTest.failedCommandDoesNotStopOthers` and the parser tests with empty input in Task 2.

---

### Task 1: Toolchain and project skeleton

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties`, `.gitignore`, `local.properties` (ignored, `sdk.dir=~/Library/Android/sdk`)
- Create: `core/build.gradle.kts` (kotlin-jvm), `core/src/test/kotlin/local/handheld/tweaks/core/SmokeTest.kt`
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/kotlin/local/handheld/tweaks/MainActivity.kt` (placeholder Compose text)

**Interfaces:**
- Produces: modules `:core` (pure JVM, package `local.handheld.tweaks.core`) and `:app` (depends on `:core`).

- [ ] **Step 1:** `brew install openjdk@17` and set `JAVA_HOME=$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home` in every Gradle command (use a `gradlew` wrapper made with `gradle wrapper --gradle-version 8.14`).
- [ ] **Step 2:** Write `SmokeTest` asserting `1 + 1 == 2`. Run `./gradlew :core:test`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 3:** Run `./gradlew :app:assembleDebug`. Expected: `app/build/outputs/apk/debug/app-debug.apk` exists.
- [ ] **Step 4:** `adb -s $DEV install -r app/build/outputs/apk/debug/app-debug.apk`, then `adb shell am start -n local.handheld.tweaks/.MainActivity`. Expected: the app opens and shows its placeholder.
- [ ] **Step 5:** Commit `chore: project skeleton`.

### Task 2: Tweak model, parsers and reconciler (core)

**Files:**
- Create: `core/src/main/kotlin/local/handheld/tweaks/core/Tweaks.kt`, `Parsers.kt`, `Reconciler.kt`
- Create: `core/src/main/resources/debloat-packages.txt` (Gradle `processResources` copies it from `docs/debloat-packages.txt`. Don't duplicate the file by hand.)
- Test: `core/src/test/kotlin/local/handheld/tweaks/core/ParsersTest.kt`, `ReconcilerTest.kt`

**Interfaces:**
- Produces:
  - `fun interface Shell { fun run(cmd: String): ShellResult }`; `data class ShellResult(val exit: Int, val out: String)`
  - `enum class TweakId { REFRESH_120, DEBLOAT, NEUTER_PROTECTED, CUTOUT_OVERRIDE, IDLE_WHITELIST }`
  - `enum class TweakStatus { APPLIED, REAPPLIED, PROTECTED, FAILED }`
  - `data class TweakResult(val id: TweakId, val status: TweakStatus, val detail: String)`
  - `object Config { val debloat: List<String>; val protected: List<String>; val cutoutPkgs: List<String>; fun idleWhitelist(ownPkg: String): List<String> }`
  - Parsers: `parsePackageList(out: String): Set<String>` (`package:` prefix stripped, CR stripped); `parseAppOpModes(out: String): Map<String, String>` (op → mode from lines like `RUN_IN_BACKGROUND: ignore` and `Uid mode: POST_NOTIFICATION: ignore`); `parseCompatEnabled(out: String, change: String, pkg: String): Boolean` (finds `packageOverrides={...pkg=true...}` in the block for `name=<change>`); `parseIdleWhitelist(out: String): Set<String>` (lines `system-excidle,pkg,uid` / `user,pkg,uid` → pkg).
  - `class Reconciler(private val shell: Shell, private val ownPkg: String) { fun reconcileAll(): List<TweakResult> }`: one result per `TweakId`, in enum order. Each tweak checks first and only runs apply commands when it has drifted. An exception or unparseable output in one tweak → `FAILED` for that tweak, and the rest continue.
  - `object Rotation { val LANDSCAPE: List<String>; val NORMAL: List<String> }`: the exact command lists from Global Constraints. `fun isLandscapeLocked(fixedOut: String, userRotation: String): Boolean` (true when `wm fixed-to-user-rotation` prints `enabled` and user_rotation is `3`).

- [ ] **Step 1: Write failing parser tests** using real captured samples (paste them from the device into the test as string literals):
  - `parsePackageList("package:a\r\npackage:b\n") == setOf("a","b")`; `parsePackageList("") == emptySet()`
  - `parseAppOpModes("RUN_IN_BACKGROUND: ignore\nUid mode: POST_NOTIFICATION: ignore\nRUN_ANY_IN_BACKGROUND: ignore")` contains all three = `"ignore"`
  - `parseCompatEnabled(sample, "OVERRIDE_LAYOUT_IN_DISPLAY_CUTOUT_MODE", "com.nendo.argosy") == true`, and `false` for a sample without the override and for `""`
  - `parseIdleWhitelist("system-excidle,com.a,1000\nuser,com.b,10234\n") == setOf("com.a","com.b")`
  - `Rotation.isLandscapeLocked("enabled", "3") == true`; `("default", "3") == false`; `("enabled", "0") == false`
- [ ] **Step 2:** Run `./gradlew :core:test`. Expected: FAIL (unresolved references).
- [ ] **Step 3:** Implement `Tweaks.kt` and `Parsers.kt` per the Interfaces block.
- [ ] **Step 4: Write failing reconciler tests** with a `FakeShell` (a map of command prefix → `ShellResult`, recording every command it's asked to run):
  - `refreshAlreadyApplied_noPutCommands`: the gets return `120.0` → status `APPLIED`, no `settings put`.
  - `refreshDrifted_reapplies`: `min_refresh_rate` returns `30.0` → both `settings put system min_refresh_rate 120.0` and `peak_refresh_rate 120.0` are run → `REAPPLIED`.
  - `debloat_onlyUninstallsPresentPackages`: the package list contains 2 debloat packages → exactly 2 `pm uninstall -k --user 0 <pkg>` commands.
  - `debloat_nonDisableIsProtected`: uninstall output `Failure: package is non-disable` → status `PROTECTED`, detail names the package.
  - `cutout_enablesMissingOverride`: compat output without the override → runs `am compat enable OVERRIDE_LAYOUT_IN_DISPLAY_CUTOUT_MODE com.nendo.argosy`.
  - `failedCommandDoesNotStopOthers`: the shell throws on the refresh get → `REFRESH_120` is `FAILED` and the other 4 results are still present.
  - `neverRunsForceStop`: across all the tests above, no recorded command contains `force-stop`, ` kill`, or `pm clear`.
- [ ] **Step 5:** Run → FAIL. Implement `Reconciler.kt`. Run `./gradlew :core:test` → PASS.
- [ ] **Step 6:** Commit `feat(core): tweak parsers and reconciler`.

### Task 3: Controller tracker with debounce (core)

**Files:**
- Create: `core/src/main/kotlin/local/handheld/tweaks/core/ControllerTracker.kt`
- Test: `core/src/test/kotlin/local/handheld/tweaks/core/ControllerTrackerTest.kt`

**Interfaces:**
- Produces:
  - `fun isController(sources: Int, isExternal: Boolean): Boolean`, using the masks from Global Constraints (`sources and 0x401 == 0x401 || sources and 0x01000010 == 0x01000010`) combined with `isExternal`.
  - `class ControllerTracker(private val debounceMs: Long = 3000, private val onChange: (connected: Boolean) -> Unit, private val schedule: (delayMs: Long, block: () -> Unit) -> Cancellable)`; `fun interface Cancellable { fun cancel() }`.
  - Methods: `fun reset(controllerIds: Set<Int>)` (sets the state without calling `onChange`), `fun added(id: Int, isController: Boolean)`, `fun removed(id: Int)`, `val connected: Boolean`.
  - `onChange(true)` fires immediately on the first controller added while disconnected. `onChange(false)` fires `debounceMs` after the set becomes empty, unless a controller is added in the meantime.

- [ ] **Step 1: Write failing tests** using a fake scheduler that records pending blocks and runs them on `advance(ms)`:
  - `isController(0x401, true) == true`; `(0x01000010, true) == true`; `(0x401, false) == false`; `(0x101 /*keyboard*/, true) == false`
  - `attach_firesImmediately`
  - `twoDevicesRemovedBackToBack_singleDetachAfterDebounce`: add 13 (not a controller) and 14 (controller), remove 14 then 13. Nothing fires at 2999 ms; exactly one `false` fires at 3000 ms.
  - `reattachWithinDebounce_noDetach`: remove, advance 1000 ms, add → no `false` ever, and no second `true`.
  - `nonControllerAdd_noChange`
- [ ] **Step 2:** Run → FAIL. Implement. Run `./gradlew :core:test` → PASS.
- [ ] **Step 3:** Commit `feat(core): controller tracker with detach debounce`.

### Task 4: Shizuku shell bridge (app)

**Files:**
- Create: `app/src/main/aidl/local/handheld/tweaks/IShellService.aidl`
- Create: `app/src/main/kotlin/local/handheld/tweaks/shizuku/ShellUserService.kt`, `ShizukuGateway.kt`
- Modify: `app/src/main/AndroidManifest.xml` (add the `rikka.shizuku.ShizukuProvider` provider with authority `${applicationId}.shizuku`, `multiprocess=false`, `exported=true`, `permission="android.permission.INTERACT_ACROSS_USERS_FULL"`), `app/build.gradle.kts` (`buildFeatures.aidl = true`, Shizuku deps)

**Interfaces:**
- Consumes: `Shell`, `ShellResult` (Task 2).
- Produces:
  - AIDL: `interface IShellService { void destroy() = 16777114; String exec(String cmd) = 1; }`. `exec` returns `"<exit>\n<stdout+stderr>"`.
  - `ShellUserService : IShellService.Stub` runs `/system/bin/sh -c cmd` with `redirectErrorStream(true)` and a 20 s timeout. `destroy()` calls `System.exit(0)`.
  - `enum class ShizukuState { NOT_RUNNING, NO_PERMISSION, READY }`
  - `object ShizukuGateway { val state: StateFlow<ShizukuState>; fun init(ctx: Context); fun requestPermission(); suspend fun shell(): Shell }`. `init` registers the sticky binder-received/dead listeners and the permission listener. `shell()` suspends until `READY`, binds the UserService once (`UserServiceArgs(ComponentName(ctx, ShellUserService::class.java)).daemon(false).processNameSuffix("shell").version(1)`), and returns a `Shell` that calls `exec` and parses the exit code from the first line.

- [ ] **Step 1:** Implement the classes per the Interfaces block. (Their behavior depends on the device, so there's no JVM test. Verify on the device.)
- [ ] **Step 2:** Add a temporary debug button to `MainActivity` that runs `id` through `ShizukuGateway.shell()` and shows the output.
- [ ] **Step 3:** Build and install. Tap the button, approve the Shizuku permission prompt, then tap again. Expected: the text contains `uid=2000(shell)`.
- [ ] **Step 4:** Remove the debug button. Commit `feat(app): shizuku shell bridge`.

### Task 5: Foreground service, boot start, rotation and logging (app)

**Files:**
- Create: `app/src/main/kotlin/local/handheld/tweaks/service/TweakService.kt`, `BootReceiver.kt`, `Notifications.kt`, `RunLog.kt`
- Modify: `AndroidManifest.xml`: permissions `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `RECEIVE_BOOT_COMPLETED`, `POST_NOTIFICATIONS`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. The service has `foregroundServiceType="specialUse"` and the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property value `"Keeps device tweaks applied and reacts to game controller connection"`. The receiver handles `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`.

**Interfaces:**
- Consumes: `Reconciler`, `Rotation`, `ControllerTracker`, `isController` (Tasks 2–3); `ShizukuGateway` (Task 4).
- Produces:
  - `TweakService` (started with `startForegroundService`). Companion: `fun start(ctx: Context)`; `const val ACTION_REAPPLY = "local.handheld.tweaks.REAPPLY"`; `const val ACTION_TOGGLE_LANDSCAPE = "local.handheld.tweaks.TOGGLE"`.
  - `object ServiceState { val lastResults: StateFlow<List<TweakResult>>; val controllerConnected: StateFlow<Boolean>; val landscapeLocked: StateFlow<Boolean>; val shizuku: StateFlow<ShizukuState> }` (the shared UI state for Tasks 6–7).
  - `RunLog.append(ctx, line: String)` writes to `filesDir/run.log`, keeping the last 500 lines. `RunLog.read(ctx): List<String>`.
- Behavior:
  - On start: enter the foreground with the ongoing notification, then wait for Shizuku and run, in order, `reconcileAll()`, the deviceidle whitelist for own package, and rotation per the current controller presence. Every result goes to the log. Any `FAILED` result posts one alert notification.
  - On input attach/detach, run the tracker callbacks and then `Rotation.LANDSCAPE` or `NORMAL`, and update the ongoing notification text.
  - `ACTION_TOGGLE_LANDSCAPE` flips between LANDSCAPE and NORMAL based on `isLandscapeLocked`.
  - `ACTION_REAPPLY` reruns `reconcileAll()` and the rotation-for-presence step.
  - All shell work runs on `Dispatchers.IO` in the service's scope.

- [ ] **Step 1:** Implement the classes per the Interfaces block.
- [ ] **Step 2: Drift test on the device.** Run `adb shell settings put system min_refresh_rate 30.0`, then `adb shell am start-foreground-service -a local.handheld.tweaks.REAPPLY -n local.handheld.tweaks/.service.TweakService`. Expected: `settings get system min_refresh_rate` → `120.0`, and `run-as local.handheld.tweaks cat files/run.log` shows `REFRESH_120 REAPPLIED`.
- [ ] **Step 3: Controller test.** Unplug the GameSir. After 3 s, `wm fixed-to-user-rotation` → `default` and `accelerometer_rotation` → `1`. Plug it in → `enabled`, and `dumpsys window displays | grep mDisplayRotation` → `ROTATION_270`.
- [ ] **Step 4: Shizuku-late test.** Stop Shizuku (`adb shell am force-stop moe.shizuku.privileged.api` is allowed here because it's only for testing Shizuku itself), then start the service. Expected: the notification says it's waiting for Shizuku and the service stays alive. Start Shizuku from its app → the log gains a new reconcile run.
- [ ] **Step 5: Reboot test.** `adb reboot`, then reconnect (rescan for the port). Expected: the service is running (`dumpsys activity services local.handheld.tweaks`) and the log has a run from after the boot.
- [ ] **Step 6:** Commit `feat(app): boot-started tweak service with controller rotation`.

### Task 6: Quick Settings tile (app)

**Files:**
- Create: `app/src/main/kotlin/local/handheld/tweaks/tile/LandscapeTileService.kt`, `app/src/main/res/drawable/ic_tile_rotation.xml`
- Modify: `AndroidManifest.xml` (the TileService with `BIND_QUICK_SETTINGS_TILE`, label "Landscape lock", and the `android.service.quicksettings.TOGGLEABLE_TILE` meta-data set to true)

**Interfaces:**
- Consumes: `ServiceState`, `TweakService.ACTION_TOGGLE_LANDSCAPE`, `ShizukuGateway.state` (Tasks 4–5).
- Produces: a tile. `onStartListening` sets the state ACTIVE/INACTIVE from `ServiceState.landscapeLocked` (UNAVAILABLE if Shizuku isn't READY) and the subtitle `"Controller connected"` when connected. `onClick` sends the toggle intent to the service, or, when unavailable, runs `startActivityAndCollapse(PendingIntent)` to open `MainActivity`. Long-press opens `MainActivity` through the activity's `android.service.quicksettings.action.QS_TILE_PREFERENCES` intent filter.

- [ ] **Step 1:** Implement, build and install. Add the tile to quick settings with `adb shell cmd statusbar add-tile local.handheld.tweaks/.tile.LandscapeTileService`.
- [ ] **Step 2:** With the controller unplugged, tap the tile through `cmd statusbar click-tile local.handheld.tweaks/.tile.LandscapeTileService`. Expected: `wm fixed-to-user-rotation` → `enabled` and `user_rotation` → `3`. Tap again → `default`. A screenshot shows the tile state matching.
- [ ] **Step 3:** Commit `feat(app): landscape lock quick settings tile`.

### Task 7: Status screen and first-run permissions (app)

**Files:**
- Modify: `app/src/main/kotlin/local/handheld/tweaks/MainActivity.kt`
- Create: `app/src/main/kotlin/local/handheld/tweaks/ui/StatusScreen.kt`

**Interfaces:**
- Consumes: `ServiceState`, `ShizukuGateway`, `RunLog`, `TweakService.start`/`ACTION_REAPPLY`.
- Produces: one Compose screen, laid out for landscape and readable at 1080 px height:
  - Shizuku row: state, plus a "Grant" button when `NO_PERMISSION` or an "Open Shizuku" button when `NOT_RUNNING`.
  - One row per `TweakId`: status and detail.
  - Controller and rotation row: "Controller: connected/not connected · Rotation: landscape-locked/normal".
  - A "Reapply now" button.
  - A collapsible log (the last 100 lines of `RunLog.read`).
  - On first launch it requests `POST_NOTIFICATIONS`, then fires `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` for its own package, then calls `TweakService.start`.

- [ ] **Step 1:** Implement, build, install and launch the app.
- [ ] **Step 2:** Take a screenshot (`adb exec-out screencap -p`). Expected: every row is visible, Shizuku shows READY, all five tweaks show APPLIED or PROTECTED, and the controller row matches reality.
- [ ] **Step 3:** Commit `feat(app): status screen`.

### Task 8: One-time device setup and end-to-end check

**Files:**
- Create: `README.md` (setup steps, how to rebuild and install, and how to undo: `pm uninstall local.handheld.tweaks`, `wm fixed-to-user-rotation default`)

- [ ] **Step 1:** Run `adb shell pm grant moe.shizuku.privileged.api android.permission.WRITE_SECURE_SETTINGS`. Then have the owner turn on "Start on boot (wireless debugging)" in Shizuku.
- [ ] **Step 2: Full reboot test without the Mac.** Before rebooting, set `min_refresh_rate 30.0` to simulate an OTA reset, then `adb reboot`. Once Wi-Fi is up: Shizuku should be running, the log should show `REFRESH_120 REAPPLIED`, and plugging in the controller should switch to landscape, lock screen included.
- [ ] **Step 3:** Update the memory note `moto-g-power-adb.md` with the app's existence and its repo path.
- [ ] **Step 4:** Commit `docs: setup and recovery README`.
