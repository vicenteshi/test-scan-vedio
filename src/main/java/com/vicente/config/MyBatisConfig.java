package com.vicente.config;

import com.vicente.entity.VideoFile;
import com.vicente.mapper.VideoFileMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

public class MyBatisConfig {
    private static final Logger log = LoggerFactory.getLogger(MyBatisConfig.class);
    private static SqlSessionFactory sqlSessionFactory;

    public static synchronized SqlSessionFactory getSqlSessionFactory() throws Exception {
        if (sqlSessionFactory != null) {
            return sqlSessionFactory;
        }
        // 1. 加载 application.properties
        Properties props = new Properties();
        try (InputStream in = MyBatisConfig.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (in == null) {
                log.warn("找不到 application.properties 文件，数据库不可用");
                return null;
            }
            props.load(in);
        } catch (Exception e) {
            log.warn("读取 application.properties 失败: {}", e.getMessage());
            return null;
        }

        // 2. 创建数据源并测试连接
        PooledDataSource dataSource = new PooledDataSource();
        dataSource.setDriver(props.getProperty("jdbc.driver"));
        dataSource.setUrl(props.getProperty("jdbc.url"));
        dataSource.setUsername(props.getProperty("jdbc.username"));
        dataSource.setPassword(props.getProperty("jdbc.password"));
        dataSource.setPoolMaximumActiveConnections(20);
        dataSource.setPoolMaximumIdleConnections(5);

        // 测试连接
        try (Connection conn = dataSource.getConnection()) {
            log.info("数据库连接测试成功");
        } catch (SQLException e) {
            log.warn("数据库连接失败: {}", e.getMessage());
            return null;
        }

        // 3. 连接成功，继续初始化 MyBatis
        try {
            Environment environment = new Environment("development",
                    new JdbcTransactionFactory(), dataSource);
            Configuration configuration = new Configuration(environment);
            configuration.setMapUnderscoreToCamelCase(true);
            // 4. 注册实体类别名（可选）
            configuration.getTypeAliasRegistry().registerAlias("VideoFile", VideoFile.class);

            // 注册 Mapper 接口
            configuration.addMapper(VideoFileMapper.class);
            // 6. 手动加载同包下的 XML 映射文件（关键！）
            String xmlResourcePath = "mapper/VideoFileMapper.xml";
            try (InputStream xmlStream = Resources.getResourceAsStream(xmlResourcePath)) {
                if (xmlStream == null) {
                    log.warn("找不到 XML 映射文件: {}", xmlResourcePath);
                    return null;
                }
                XMLMapperBuilder mapperBuilder = new XMLMapperBuilder(
                        xmlStream, configuration, xmlResourcePath, configuration.getSqlFragments());
                mapperBuilder.parse();
                log.info("成功加载 XML 映射文件：{}", xmlResourcePath);
            }
            // 7. 构建 SqlSessionFactory
            sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
            // 建表
            try (SqlSession session = sqlSessionFactory.openSession(true)) {
                session.getMapper(VideoFileMapper.class).createTableIfNotExist();
                log.info("数据库表初始化成功");
            } catch (Exception e) {
                log.warn("建表失败: {}", e.getMessage());
                sqlSessionFactory = null;
                return null;
            }
        }catch (Exception e) {
            log.warn("MyBatis 初始化失败: {}", e.getMessage());
            sqlSessionFactory = null;
            return null;
        }
        return sqlSessionFactory;
    }

}
