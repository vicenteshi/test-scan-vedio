package com.vicente.mapper;

import com.vicente.entity.ImageFile;

import java.util.List;

/**
 * @author sws
 * @description 图片操作
 * @date 2026/05/28
 */
public interface ImageFileMapper {

    void createTableIfNotExist();
    void batchInsertOrUpdate(List<ImageFile> list);
    void truncateTable();
}
