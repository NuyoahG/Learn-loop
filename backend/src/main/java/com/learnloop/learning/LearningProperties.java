package com.learnloop.learning;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 第一条学习闭环的配置。凭据沿用解析服务内部凭据，不建立第二套身份。
 * enabled 默认 false：必须先执行增量迁移，再显式启用，旧库才能安全启动。
 * 本类故意不生成 toString，避免调试日志意外包含密钥。
 */
@Component
@ConfigurationProperties(prefix = "learnloop.learning")
public class LearningProperties {
    private boolean enabled = false;
    private boolean workerEnabled = true;
    private String bktBaseUrl = "http://127.0.0.1:8001";
    private String serviceKey = "";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public boolean isWorkerEnabled() { return workerEnabled; }
    public void setWorkerEnabled(boolean value) { workerEnabled = value; }
    public String getBktBaseUrl() { return bktBaseUrl; }
    public void setBktBaseUrl(String value) { bktBaseUrl = value; }
    public String getServiceKey() { return serviceKey; }
    public void setServiceKey(String value) { serviceKey = value; }
}
