package io.github.astromg01.rbxmicrofix.migrator.shizuku;

interface ISnapshotExportService {
    String exportLegacySnapshot();
    void destroy() = 16777114;
    void exit() = 1;
}
