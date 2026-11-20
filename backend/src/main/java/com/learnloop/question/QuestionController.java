package com.learnloop.question;

import com.learnloop.auth.LoginUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 题目查询接口的入口层（Controller）。
 *
 * <p>职责边界：本类只做「协议转换 + 身份交付」两件事——
 * 把 HTTP 请求（路径变量、登录态）翻译成 Service 需要的参数，
 * 再把 Service 返回的业务对象包装成 HTTP 响应。
 * 所有权校验、抛什么状态码等业务规则一律放在 {@link QuestionService}，
 * 避免接口层和服务层重复表达同一条约束。
 *
 * <p>对外契约：GET /api/questions/{id}，成功返回 200 与题目 JSON；
 * 未登录 401、题目编号非法 400、题目不存在或非本人 404（由 Service 决定）。
 */
@RestController// 声明这是一个 RESTful 风格的 Controller，返回值直接写入 HTTP 响应体
@RequestMapping("/api/questions")// 统一前缀，方法上只写剩余路径
public class QuestionController {

    /** 依赖只暴露接口行为，Controller 不持有 Mapper，也不写 SQL。 */
    private final QuestionService questionService;

    /**
     * 构造器注入（本项目统一采用，不用字段 @Autowired）：
     * <ul>
     *   <li>依赖由 Spring 在创建本 Bean 时解析并传入，调用方无需反射即可构造对象，单测可直接喂假实现；</li>
     *   <li>配合 final 字段，保证依赖一旦赋值不再改变，也不出现「半初始化」的 Bean；</li>
     *   <li>依赖变多时构造器会立刻变得臃肿，可作为「这个类职责过重」的早提醒。</li>
     * </ul>
     */
    public QuestionController(QuestionService questionService) {
        this.questionService = questionService;// 依赖注入
    }

    /**
     * 按编号查询单道题目。
     *
     * @param id          题目编号，来自路径，单位为正整数；非正数交给 Service 判定为 400。
     * @param currentUser 已登录用户，由 Spring Security 从认证上下文注入，前端无法伪造。
     * @return 200 响应，body 为 {@link QuestionResponse}；
     *         注意返回类型用 ResponseEntity 而非裸对象，方便后续按业务追加状态码或响应头。
     */
    @GetMapping("/{id}")// 声明这是一个 GET 请求的处理方法，路径为 /api/questions/{id}
    public ResponseEntity<QuestionResponse> getById(
            @PathVariable("id") Long id,// 从路径中提取的题目编号，单位为正整数
            @AuthenticationPrincipal LoginUser currentUser// 当前登录用户，由 Spring Security 提供
    ) {
        // currentUser 由 Spring Security 从认证上下文中提供。
        // 未登录请求会先被现有安全配置拒绝，不会进入这个方法。
        // 这里仍然把 userId 显式传给 Service，是为了让「按谁的身份查」在方法签名上可见，
        // 防止将来新增不校验登录的调用路径时悄悄退化成全表查询。
        QuestionResponse question = questionService.getQuestion(
                currentUser.getUserId(),
                id
        );

        return ResponseEntity.ok(question);
    }
}
