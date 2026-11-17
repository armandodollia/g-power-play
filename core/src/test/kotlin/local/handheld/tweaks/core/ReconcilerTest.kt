package local.handheld.tweaks.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconcilerTest {
    private val own = "local.handheld.tweaks"
    private val allOk = Config.protectedOps.joinToString("\n") { "$it: ignore" }
    private val shells = mutableListOf<FakeShell>()

    /** A device where every tweak is already in place. */
    private fun healthy() = FakeShell().apply {
        on("settings get system min_refresh_rate", "120.0")
        on("settings get system peak_refresh_rate", "120.0")
        on("pm list packages --user 0", (Config.protected + Config.cutoutPkgs + own).joinToString("\n") { "package:$it" })
        on("appops get", allOk)
        on("dumpsys platform_compat",
            "ChangeId(332679525; name=OVERRIDE_LAYOUT_IN_DISPLAY_CUTOUT_MODE; disabled; packageOverrides={com.nendo.argosy=true}; overridable)")
        on("dumpsys deviceidle whitelist", Config.idleWhitelist(own).joinToString("\n") { "user,$it,10000" })
    }.also { shells += it }

    private fun results(s: FakeShell) = Reconciler(s, own).reconcileAll().associateBy { it.id }

    @After fun neverRunsForceStop() {
        shells.flatMap { it.ran }.forEach { cmd ->
            assertFalse(cmd, cmd.contains("force-stop") || cmd.contains(" kill") || cmd.contains("pm clear"))
            assertFalse("am compat must not kill the app: $cmd", cmd.startsWith("am compat") && !cmd.contains("--no-kill"))
        }
    }

    @Test fun healthyDevice_allAppliedAndNoWrites() {
        val s = healthy()
        val r = results(s)
        assertEquals(TweakId.entries.toList(), Reconciler(healthy(), own).reconcileAll().map { it.id })
        r.values.forEach { assertEquals(it.toString(), TweakStatus.APPLIED, it.status) }
        assertTrue(s.ran.none { it.startsWith("settings put") || it.startsWith("pm uninstall") || it.startsWith("appops set") || it.startsWith("am compat") || it.contains("whitelist +") })
    }

    @Test fun refreshDrifted_reapplies() {
        val s = healthy().on("settings get system min_refresh_rate", "30.0")
        assertEquals(TweakStatus.REAPPLIED, results(s)[TweakId.REFRESH_120]!!.status)
        assertTrue(s.ran.contains("settings put system min_refresh_rate 120.0"))
        assertTrue(s.ran.contains("settings put system peak_refresh_rate 120.0"))
    }

    @Test fun debloat_onlyUninstallsPresentPackages() {
        val present = Config.debloat.filterNot { it in Config.protected }.take(2)
        val s = healthy().on("pm list packages --user 0", (present + Config.cutoutPkgs + own).joinToString("\n") { "package:$it" })
            .on("pm uninstall", "Success")
        assertEquals(TweakStatus.REAPPLIED, results(s)[TweakId.DEBLOAT]!!.status)
        assertEquals(present.map { "pm uninstall -k --user 0 $it" }, s.ran.filter { it.startsWith("pm uninstall") })
    }

    @Test fun debloat_skipsKnownProtectedPackages() {
        val s = healthy().on("pm uninstall", "Success")
        results(s)
        assertTrue(s.ran.none { it.startsWith("pm uninstall") })
    }

    @Test fun debloat_nonDisableIsProtected() {
        val pkg = Config.debloat.first { it !in Config.protected }
        val s = healthy().on("pm list packages --user 0", (Config.cutoutPkgs + own + pkg).joinToString("\n") { "package:$it" })
            .on("pm uninstall", "Failure: package is non-disable", exit = 1)
        val r = results(s)[TweakId.DEBLOAT]!!
        assertEquals(TweakStatus.PROTECTED, r.status)
        assertTrue(r.detail.contains(pkg))
    }

    @Test fun neuter_setsMissingOps() {
        val s = healthy().on("appops get com.motorola.paks", "RUN_IN_BACKGROUND: allow")
        assertEquals(TweakStatus.REAPPLIED, results(s)[TweakId.NEUTER_PROTECTED]!!.status)
        Config.protectedOps.forEach { assertTrue(s.ran.contains("appops set com.motorola.paks $it ignore")) }
        assertTrue(s.ran.contains("pm revoke com.motorola.paks android.permission.POST_NOTIFICATIONS"))
    }

    @Test fun cutout_enablesMissingOverride() {
        val s = healthy().on("dumpsys platform_compat", "ChangeId(332679525; name=OVERRIDE_LAYOUT_IN_DISPLAY_CUTOUT_MODE; disabled; overridable)")
        assertEquals(TweakStatus.REAPPLIED, results(s)[TweakId.CUTOUT_OVERRIDE]!!.status)
        assertTrue(s.ran.contains("am compat enable --no-kill OVERRIDE_LAYOUT_IN_DISPLAY_CUTOUT_MODE com.nendo.argosy"))
    }

    @Test fun idle_addsMissingPackages() {
        val s = healthy().on("dumpsys deviceidle whitelist", "")
        assertEquals(TweakStatus.REAPPLIED, results(s)[TweakId.IDLE_WHITELIST]!!.status)
        assertTrue(s.ran.contains("dumpsys deviceidle whitelist +$own"))
        assertTrue(s.ran.contains("dumpsys deviceidle whitelist +com.nendo.argosy"))
    }

    @Test fun failedApplyIsFailed() {
        val s = healthy().on("settings get system min_refresh_rate", "30.0").on("settings put", "denied", exit = 255)
        val r = results(s)[TweakId.REFRESH_120]!!
        assertEquals(TweakStatus.FAILED, r.status)
        assertTrue(r.detail.contains("denied"))
    }

    @Test fun failedCommandDoesNotStopOthers() {
        val s = healthy().apply { throwOn += "settings get system min_refresh_rate" }
        val r = Reconciler(s, own).reconcileAll()
        assertEquals(5, r.size)
        assertEquals(TweakStatus.FAILED, r.first { it.id == TweakId.REFRESH_120 }.status)
        assertTrue(r.filter { it.id != TweakId.REFRESH_120 }.all { it.status == TweakStatus.APPLIED })
    }

    @Test fun emptyRefreshOutputIsTreatedAsDrift() {
        val s = healthy().on("settings get system min_refresh_rate", "")
        assertEquals(TweakStatus.REAPPLIED, results(s)[TweakId.REFRESH_120]!!.status)
    }
}
