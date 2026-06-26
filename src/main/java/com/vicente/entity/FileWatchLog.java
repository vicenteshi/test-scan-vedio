package com.vicente.entity;

import java.time.LocalDateTime;

/**
 * @author sws
 * @description TODO
 * @date 2026/06/26
 */

public class FileWatchLog {
    private Long id;
    private String filePath;
    private String eventType;  // CREATE, MODIFY, DELETE
    private LocalDateTime eventTime;
    private Integer isProcessed; // 0-未处理, 1-已处理
    private LocalDateTime processedTime;
    private String remark;

    // getters and setters (略)

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public LocalDateTime getEventTime() {
        return eventTime;
    }

    public void setEventTime(LocalDateTime eventTime) {
        this.eventTime = eventTime;
    }

    public Integer getIsProcessed() {
        return isProcessed;
    }

    public void setIsProcessed(Integer isProcessed) {
        this.isProcessed = isProcessed;
    }

    public LocalDateTime getProcessedTime() {
        return processedTime;
    }

    public void setProcessedTime(LocalDateTime processedTime) {
        this.processedTime = processedTime;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
