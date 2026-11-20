package com.learnloop.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 凭据仅从环境变量进入进程；配置类不生成包含凭据的 toString。 */
@Component
@EnableScheduling
@ConfigurationProperties(prefix = "learnloop.material.parse")
public class MaterialParseProperties {
    private String baseUrl = "http://127.0.0.1:8001";
    private String serviceKey = "";
    private boolean recoveryEnabled = true;
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String value) { baseUrl = value; }
    public String getServiceKey() { return serviceKey; }
    public void setServiceKey(String value) { serviceKey = value; }
    public boolean isRecoveryEnabled() { return recoveryEnabled; }
    public void setRecoveryEnabled(boolean value) { recoveryEnabled = value; }
}
