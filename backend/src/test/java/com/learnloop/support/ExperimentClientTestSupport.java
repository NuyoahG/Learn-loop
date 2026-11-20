package com.learnloop.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestClient;

/**
 * 仅给测试类路径中的个人 BKT 实验客户端提供 Builder。
 * 正式 JAR 不包含 src/test 的实验类，也不需要这个 Bean；因此不在生产配置
 * 中为测试问题添加依赖，也不修改用户正在学习的实验源码。
 */
@TestConfiguration
public class ExperimentClientTestSupport {
    @Bean
    RestClient.Builder experimentRestClientBuilder() { return RestClient.builder(); }
}
