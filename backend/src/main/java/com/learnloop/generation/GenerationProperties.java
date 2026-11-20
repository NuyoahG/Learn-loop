package com.learnloop.generation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 新功能默认关闭，旧数据库与原解析环境无须立即升级。密钥只通过进程环境配置。 */
@Component
@ConfigurationProperties(prefix = "learnloop.generation")
public class GenerationProperties {
    private boolean enabled;
    private boolean workerEnabled = true;
    private String baseUrl = "http://127.0.0.1:8001";
    private String serviceKey = "";
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isWorkerEnabled() { return workerEnabled; }
    public void setWorkerEnabled(boolean workerEnabled) { this.workerEnabled = workerEnabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getServiceKey() { return serviceKey; }
    public void setServiceKey(String serviceKey) { this.serviceKey = serviceKey; }
}
