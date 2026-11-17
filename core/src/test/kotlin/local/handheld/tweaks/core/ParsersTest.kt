package local.handheld.tweaks.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsersTest {
    @Test fun packageList_stripsPrefixAndCr() =
        assertEquals(setOf("a", "b"), parsePackageList("package:a\r\npackage:b\n"))

    @Test fun packageList_empty() = assertEquals(emptySet<String>(), parsePackageList(""))

    // Real `appops get com.motorola.paks` output (trimmed): uid section, then package section.
    private val appops = """
        Uid mode: COARSE_LOCATION: ignore
        POST_NOTIFICATION: ignore
        CAMERA: ignore
        POST_NOTIFICATION: allow
        RUN_IN_BACKGROUND: ignore
        RUN_ANY_IN_BACKGROUND: ignore
        USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER: default; rejectTime=+51m30s106ms ago
    """.trimIndent()

    @Test fun appOps_readsBothSectionsAndRestrictiveWins() {
        val m = parseAppOpModes(appops)
        assertEquals("ignore", m["POST_NOTIFICATION"])
        assertEquals("ignore", m["RUN_IN_BACKGROUND"])
        assertEquals("ignore", m["RUN_ANY_IN_BACKGROUND"])
        assertEquals("ignore", m["COARSE_LOCATION"])
        assertEquals("default", m["USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER"])
    }

    @Test fun appOps_empty() = assertEquals(emptyMap<String, String>(), parseAppOpModes(""))

    private val compat = """
        ChangeId(332679525; name=OVERRIDE_LAYOUT_IN_DISPLAY_CUTOUT_MODE; disabled; packageOverrides={com.nendo.argosy=true}; rawOverrides={com.nendo.argosy=true}; overridable)
        ChangeId(149994052; name=HIDE_MAXTARGETSDK_Q_HIDDEN_APIS; enableSinceTargetSdk=30)
        ChangeId(176926771; name=DOWNSCALE_60; disabled; packageOverrides={com.other=true}; overridable)
    """.trimIndent()

    @Test fun compat_enabledForPackage() =
        assertTrue(parseCompatEnabled(compat, "OVERRIDE_LAYOUT_IN_DISPLAY_CUTOUT_MODE", "com.nendo.argosy"))

    @Test fun compat_otherChangesOverrideDoesNotCount() =
        assertFalse(parseCompatEnabled(compat, "OVERRIDE_LAYOUT_IN_DISPLAY_CUTOUT_MODE", "com.other"))

    @Test fun compat_withoutOverride() = assertFalse(
        parseCompatEnabled(
            "ChangeId(332679525; name=OVERRIDE_LAYOUT_IN_DISPLAY_CUTOUT_MODE; disabled; overridable)",
            "OVERRIDE_LAYOUT_IN_DISPLAY_CUTOUT_MODE", "com.nendo.argosy",
        )
    )

    @Test fun compat_empty() =
        assertFalse(parseCompatEnabled("", "OVERRIDE_LAYOUT_IN_DISPLAY_CUTOUT_MODE", "com.nendo.argosy"))

    @Test fun idleWhitelist() = assertEquals(
        setOf("com.a", "com.b"),
        parseIdleWhitelist("system-excidle,com.a,1000\nuser,com.b,10234\n"),
    )

    @Test fun rotation_lockedOnlyWhenEnabledAnd3() {
        assertTrue(Rotation.isLandscapeLocked("enabled", "3"))
        assertTrue(Rotation.isLandscapeLocked("enabled\n", "3\r\n"))
        assertFalse(Rotation.isLandscapeLocked("default", "3"))
        assertFalse(Rotation.isLandscapeLocked("enabled", "0"))
    }
}
