package com.learnloop.experiments.bkt;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 负责与 Python BKT 接口通信。
 *封装可复用的 Spring 客户端
 * 其他 Java 代码只需要调用 submitAnswer，
 * 不需要每次重新编写 URL、请求头、JSON 转换等细节。
 */
@Component
public class BktClient {

    // 创建一次，后续请求重复使用。
    private final RestClient restClient;

    /**
     * 构造器注入：
     * Spring 创建 BktClient 时，提供需要的依赖。
     *
     * builder：用于构建 Spring HTTP 客户端。
     * baseUrl：Python 服务的基础地址。
     *
     * @Value 中冒号后面是默认值。
     * 当前没有提供配置时，使用本机实验服务的 8002 端口。
     */
    public BktClient(
            RestClient.Builder builder,
            @Value("${learnloop.bkt.base-url:http://127.0.0.1:8002}")
            String baseUrl
    ) {

        // 这里继续使用 JDK 提供的底层 HTTP 客户端。
        // 设置连接超时，避免连接不可用的服务时一直等待。
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        // 把 JDK 客户端交给 Spring 使用。
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(httpClient);

        // 设置读取响应的超时。
        requestFactory.setReadTimeout(Duration.ofSeconds(10));

        // clone 后再定制，避免改动传入 builder 的配置，
        // 给以后增加其他 Python 客户端留出空间。
        this.restClient = builder.clone()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    /**
     * 提交一次答题事件，并返回已经转换好的 Java 响应对象。
     */
    public BktAnswerResponse submitAnswer(BktAnswerRequest event) {

        BktAnswerResponse response = restClient
                // 使用 HTTP POST 方法。
                .post()

                // 与 baseUrl 合并，得到完整接口地址。
                .uri("/demo/answers")

                // 声明我们发送的是 JSON。
                .contentType(MediaType.APPLICATION_JSON)

                // 声明我们希望接收 JSON。
                .accept(MediaType.APPLICATION_JSON)

                // 直接传 Java 对象。
                // Spring 的消息转换器负责把它转换为 JSON。
                .body(event)

                // 进入响应处理流程。
                // 默认情况下，4xx、5xx 会转换为异常。
                .retrieve()

                // 读取响应，并把 JSON 转成指定的 Java 类型。
                .body(BktAnswerResponse.class);

        // HTTP 请求成功，也可能遇到空响应或缺少 state 的响应。
        // 这里先检查最基本的结构，给出明确错误。
        if (response == null || response.state() == null) {
            throw new IllegalStateException(
                    "BKT 接口没有返回有效的学习状态"
            );
        }

        return response;
    }
}