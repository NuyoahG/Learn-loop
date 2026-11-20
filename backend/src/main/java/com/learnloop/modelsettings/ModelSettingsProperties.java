package com.learnloop.modelsettings;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 用户模型设置单独启用，避免升级代码便立即读取尚未迁移的新表。
 * 主密钥仅由服务器启动环境注入；不得写入浏览器、数据库、仓库或命令行参数。
 */
@Component
@ConfigurationProperties(prefix = "learnloop.model-settings")
public class ModelSettingsProperties {
    private boolean enabled;
    private String masterKey = "";
    private String model = "deepseek-flash";
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getMasterKey() { return masterKey; }
    public void setMasterKey(String masterKey) { this.masterKey = masterKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
}
