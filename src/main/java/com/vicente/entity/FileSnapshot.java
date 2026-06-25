package com.vicente.entity;

import java.time.LocalDateTime;

/**
 * @author sws
 * @description 内部快照类,用于判断增量查询
 * @date 2026/06/25
 */
public class FileSnapshot {
    private long size;
    private LocalDateTime modificationTime;
    private LocalDateTime lastScanTime;  // 仅用于清理逻辑，不用于增量判断

    public FileSnapshot(long size, LocalDateTime modTime, LocalDateTime lastScan) {
        this.size = size;
        this.modificationTime = modTime;
        this.lastScanTime = lastScan;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public LocalDateTime getModificationTime() {
        return modificationTime;
    }

    public void setModificationTime(LocalDateTime modificationTime) {
        this.modificationTime = modificationTime;
    }

    public LocalDateTime getLastScanTime() {
        return lastScanTime;
    }

    public void setLastScanTime(LocalDateTime lastScanTime) {
        this.lastScanTime = lastScanTime;
    }
}
