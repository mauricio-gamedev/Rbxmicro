package io.github.astromg01.rbxmicrofix.migrator.shizuku;

interface ISnapshotExportService {
    String exportLegacySnapshot() = 2;
    void destroy() = 16777114;
    void exit() = 1;
}
