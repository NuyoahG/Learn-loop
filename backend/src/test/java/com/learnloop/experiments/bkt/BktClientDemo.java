package com.learnloop.experiments.bkt;

import java.util.UUID;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * 手动运行的客户端实验。
 *
 * 只创建本实验需要的 Spring 容器，
 * 用来观察 DTO 转换和客户端调用。
 */
public class BktClientDemo {

    public static void main(String[] args) {

        // 创建一个空的 Spring 容器。
        // try 结束时，会自动关闭这个容器。
        try (var context =
                     new AnnotationConfigApplicationContext()) {

            // 当前使用的是最小 Spring 容器。
            // 因此，由我们明确注册一个 RestClient.Builder，
            // 供 BktClient 的构造方法注入使用。
            context.registerBean(
                    // 第一个参数：这个 Bean 的类型。
                    RestClient.Builder.class,

                    // 第二个参数：创建这个 Bean 的方法。
                    // () 明确表示：这个函数不接收参数。
                    // 调用后，返回一个 RestClient.Builder 对象。
                    () -> RestClient.builder()
            );
            // 告诉 Spring：请管理 BktClient 这个组件。
            context.register(BktClient.class);

            // 完成容器初始化：
            // 创建组件，解析 @Value，并完成构造器注入。
            context.refresh();

            // 从容器中获取创建好的客户端。
            BktClient client = context.getBean(BktClient.class);

            // 这次实验产生一个新的事件 ID。
            // 后面的重复提交继续使用这个 ID。
            String eventId = "java_" + UUID.randomUUID();

            BktAnswerRequest request = new BktAnswerRequest(
                    eventId,
                    101L,
                    true,
                    false
            );

            // 第一次提交：正常处理事件。
            BktAnswerResponse first =
                    client.submitAnswer(request);

            printResult("第一次提交", first);

            // 原样重复提交：继续使用同一个请求对象。
            BktAnswerResponse repeated =
                    client.submitAnswer(request);

            printResult("原样重复提交", repeated);

            // record 自动生成 equals。
            // 嵌套的 State 也是 record，因此可以比较完整响应内容。
            boolean sameResult = first.equals(repeated);

            System.out.println(
                    "重复请求返回相同结果：" + sameResult
            );
            System.out.println();

            if (!sameResult) {
                throw new IllegalStateException(
                        "重复请求返回结果不一致，需要检查"
                );
            }

            // 保留事件 ID，故意把 correct 从 true 改成 false。
            // 这是在模拟“同一个事件 ID 对应了不同内容”。
            BktAnswerRequest conflictingRequest =
                    new BktAnswerRequest(
                            eventId,
                            101L,
                            false,
                            false
                    );

            boolean conflictReceived = false;

            try {
                client.submitAnswer(conflictingRequest);

            } catch (HttpClientErrorException.Conflict ex) {

                // RestClient 默认把 HTTP 409 转成这个异常。
                // 这是本实验预期的结果，所以在这里捕获并展示。
                conflictReceived = true;

                System.out.println("相同事件 ID，修改答案");
                System.out.println(
                        "HTTP 状态码：" + ex.getStatusCode().value()
                );
                System.out.println(
                        "响应内容：" + ex.getResponseBodyAsString()
                );
            }

            // 如果修改内容后服务仍然接受请求，
            // 就说明本次冲突检查没有通过。
            if (!conflictReceived) {
                throw new IllegalStateException(
                        "预期收到 409，但接口没有返回冲突"
                );
            }
        }
    }

    /**
     * 展示已经转换为 Java 对象的结果。
     *
     * 注意：这里没有手动拆分 JSON 字符串。
     */
    private static void printResult(
            String title,
            BktAnswerResponse response
    ) {

        System.out.println(title);

        System.out.println(
                "事件 ID：" + response.eventId()
        );

        System.out.println(
                "处理结果：" + response.status()
        );

        System.out.println(
                "考点：" + response.state().conceptId()
        );

        System.out.printf(
                "掌握估计：%.6f%n",
                response.state().pMastery()
        );

        System.out.println(
                "有效作答数：" + response.state().evidenceCount()
        );

        System.out.println();
    }
}