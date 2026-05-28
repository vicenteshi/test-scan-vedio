package com.vicente.entity;

import java.time.LocalDateTime;

/**
 * @author sws
 * @description TODO
 * @date 2026/05/26
 */
public class VideoFile {
    private Long id;
    private String fileName;
    private LocalDateTime creationDate;
    private LocalDateTime modificationDate;
    private LocalDateTime lastAccessTime;
    private String fileFormat;
    private Long fileSize;
    private String fileMd5;
    // 存储绝对路径，便于定位
    private String filePath;

    // 新增字段
    private String fileSizeFormat;
    private Float fileScore;
    // 主演
    private String actor;
    // 视频编号
    private String videoCode;
    // 视频编号
    private Integer videoDuration;

    // getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public LocalDateTime getCreationDate() { return creationDate; }
    public void setCreationDate(LocalDateTime creationDate) { this.creationDate = creationDate; }
    public LocalDateTime getModificationDate() { return modificationDate; }
    public void setModificationDate(LocalDateTime modificationDate) { this.modificationDate = modificationDate; }
    public LocalDateTime getLastAccessTime() { return lastAccessTime; }
    public void setLastAccessTime(LocalDateTime lastAccessTime) { this.lastAccessTime = lastAccessTime; }
    public String getFileFormat() { return fileFormat; }
    public void setFileFormat(String fileFormat) { this.fileFormat = fileFormat; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public String getFileMd5() { return fileMd5; }
    public void setFileMd5(String fileMd5) { this.fileMd5 = fileMd5; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public String getFileSizeFormat() {
        return fileSizeFormat;
    }

    public void setFileSizeFormat(String fileSizeFormat) {
        this.fileSizeFormat = fileSizeFormat;
    }

    public Float getFileScore() {
        return fileScore;
    }

    public void setFileScore(Float fileScore) {
        this.fileScore = fileScore;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }

    public String getVideoCode() {
        return videoCode;
    }

    public void setVideoCode(String videoCode) {
        this.videoCode = videoCode;
    }

    public Integer getVideoDuration() {
        return videoDuration;
    }

    public void setVideoDuration(Integer videoDuration) {
        this.videoDuration = videoDuration;
    }
}
