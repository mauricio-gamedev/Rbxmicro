package io.github.astromg01.rbxmicrofix.shizuku;

interface IRbxShellService {
    void destroy() = 16777114;
    void exit() = 1;

    String getCurrentGameMode();
    String getCurrentOverlayConfig();
    String applyRenderingProfile(
        String downscaleFactor,
        String fpsOverride,
        boolean useAngle,
        String previousGameMode,
        String previousOverlayConfig
    );
    String restoreOriginalState(String previousGameMode, String previousOverlayConfig);
    String forceStopRoblox();
}
