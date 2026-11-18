package io.github.astromg01.rbxmicrofix.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameOverlayConfigTest {
    @Test
    fun createsPerformanceModeWhenNoOverlayExists() {
        assertEquals(
            "mode=2,downscaleFactor=0.85,fps=disable,useAngle=false",
            GameOverlayConfig.withPerformanceProfile(null, "0.85", "disable", false),
        )
    }

    @Test
    fun replacesOnlyPerformanceModeAndPreservesBatteryMode() {
        val original = "mode=2,downscaleFactor=0.9:mode=3,downscaleFactor=0.5,fps=30"
        val result = GameOverlayConfig.withPerformanceProfile(original, "0.8", "60", true)
        assertEquals(
            "mode=3,downscaleFactor=0.5,fps=30:mode=2,downscaleFactor=0.8,fps=60,useAngle=true",
            result,
        )
    }

    @Test
    fun preservesUnknownPerformanceKeys() {
        val original = "mode=2,downscaleFactor=0.7,loadingBoost=3000,customKey=value:mode=3,fps=30"
        val result = GameOverlayConfig.withPerformanceProfile(original, "0.9", "disable", false)
        assertTrue(result.contains("loadingBoost=3000"))
        assertTrue(result.contains("customKey=value"))
        assertTrue(result.contains("mode=3,fps=30"))
        assertTrue(result.contains("downscaleFactor=0.9"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnlistedScale() {
        GameOverlayConfig.withPerformanceProfile(null, "0.77", "disable", false)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnlistedFpsOverride() {
        GameOverlayConfig.withPerformanceProfile(null, "0.85", "144", false)
    }
}

class PerformanceProfileTest {
    @Test
    fun profilesUseSupportedAndroidDownscaleValues() {
        assertTrue(PerformanceProfile.entries.all {
            it.downscaleFactor in GameOverlayConfig.allowedDownscaleFactors
        })
    }

    @Test
    fun parsesGameModeIntegerValues() {
        assertEquals(2, GameModeParser.parseMode("2"))
        assertEquals(1, GameModeParser.parseMode(" 1 "))
        assertEquals(0, GameModeParser.parseMode("unsupported"))
        assertEquals(2, GameModeParser.parseMode("PERFORMANCE"))
        assertEquals(3, GameModeParser.parseMode("battery"))
        assertEquals(4, GameModeParser.parseMode("custom"))
        assertEquals(null, GameModeParser.parseMode("8"))
    }

    @Test
    fun parsesCurrentModeFromAospListModesOutput() {
        val output = "com.roblox.client current mode: performance, available game modes: [standard, performance]"
        assertEquals(2, GameModeParser.parseCurrentMode(output))
    }

    @Test
    fun parsesUnsupportedModeFromAospListModesOutput() {
        assertEquals(
            0,
            GameModeParser.parseCurrentMode(
                "com.roblox.client current mode: unsupported, available game modes: [unsupported]",
            ),
        )
    }
}
