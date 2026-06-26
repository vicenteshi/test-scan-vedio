package com.vicente.mapper;

import com.vicente.entity.VideoFile;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * @author sws
 * @description mapper类
 * @date 2026/05/26
 */
public interface VideoFileMapper {
    // 单个插入或更新（保留，批量中不用但可作为备用）
    void insertOrUpdate(VideoFile videoFile);

    // 批量插入或更新
    void batchInsertOrUpdate(@Param("list") List<VideoFile> list);

    // 创建表（增加 actor 和 video_code 字段）
    void createTableIfNotExist();

    // 可选：清空表（测试用）
    void truncateTable();

    VideoFile selectByFilePath(@Param("filePath") String filePath);

    List<VideoFile> selectAllSnapshots();

    int deleteByFilePath(@Param("filePath") String filePath);


}
