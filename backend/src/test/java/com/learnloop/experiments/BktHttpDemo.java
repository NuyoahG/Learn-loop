package com.learnloop.experiments;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

public class BktHttpDemo {

    // 重复使用同一个客户端
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public static void main(String[] args) throws Exception {
        // 三次请求共用一个事件 ID
        String eventId = "java_" + UUID.randomUUID();

        HttpResponse<String> first = sendAnswer(eventId, true);
        printResponse("第一次提交", first);

        HttpResponse<String> repeated = sendAnswer(eventId, true);
        printResponse("原样重复提交", repeated);

        HttpResponse<String> conflict = sendAnswer(eventId, false);
        printResponse("相同事件 ID，修改答案", conflict);
    }

    private static HttpResponse<String> sendAnswer(
            String eventId,
            boolean correct
    ) throws Exception {

        String json = """
                {
                  "event_id": "%s",
                  "user_id": 101,
                  "correct": %s,
                  "assisted": false
                }
                """.formatted(eventId, correct);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(
                        "http://127.0.0.1:8002/demo/answers"
                ))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        json,
                        StandardCharsets.UTF_8
                ))
                .build();

        return CLIENT.send(
                request,
                HttpResponse.BodyHandlers.ofString(
                        StandardCharsets.UTF_8
                )
        );
    }

    private static void printResponse(
            String title,
            HttpResponse<String> response
    ) {
        System.out.println(title);
        System.out.println("HTTP 状态码：" + response.statusCode());
        System.out.println("响应内容：" + response.body());
        System.out.println();
    }
}