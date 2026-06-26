package com.vicente.mapper;

import com.vicente.entity.FileWatchLog;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * @author sws
 * @description 监听日志记录
 * @date 2026/06/26
 */
public interface FileWatchLogMapper {

    int insertLog(FileWatchLog log);

    int markProcessed(@Param("filePath") String filePath,
                      @Param("eventTime") LocalDateTime eventTime);

    //批量标记
    int markAllProcessedByPathAndType(@Param("filePath") String filePath,
                                      @Param("eventType") String eventType);
}
