package com.learnloop.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.nio.file.Path;

/**
 * 资料模块的外部配置。原件属于运行数据，不能写进 static、target 或 JAR。
 * 目录必须为绝对路径，避免从 IDEA 和 java -jar 启动时写进不同位置。
 * 本阶段只增加上传与查询；Python 地址、密钥在后续阶段才引入。
 */
@Component
@ConfigurationProperties(prefix = "learnloop.material")
public class MaterialProperties {
    private Path storageRoot;
    public Path getStorageRoot() { return storageRoot; }
    public void setStorageRoot(Path storageRoot) { this.storageRoot = storageRoot; }
}
