package io.github.astromg01.rbxmicrofix.shizuku;

interface IRbxShellService {
    void destroy() = 16777114;
    void exit() = 1;

    String getCurrentGameMode() = 2;
    String getCurrentOverlayConfig() = 3;
    String applyRenderingProfile(
        String downscaleFactor,
        String fpsOverride,
        boolean useAngle,
        String previousGameMode,
        String previousOverlayConfig
    ) = 4;
    String restoreOriginalState(String previousGameMode, String previousOverlayConfig) = 5;
    String forceStopRoblox() = 6;
}
