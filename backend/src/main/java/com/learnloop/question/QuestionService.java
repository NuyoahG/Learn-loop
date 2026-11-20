package com.learnloop.question;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service//往spring中创建service对象
public class QuestionService {

    private final QuestionMapper questionMapper;

    public QuestionService(QuestionMapper questionMapper) {
        this.questionMapper = questionMapper;
    }

    public QuestionResponse getQuestion(Long userId, Long id) {
        // 业务查询必须带真实用户，不能在缺少身份时退回全表查询。
        if (userId == null || userId <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "请先登录"
            );
        }

        // 身份和资源编号是两个不同参数，分别校验。
        if (id == null || id <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "题目编号必须是正整数"
            );
        }

        QuestionResponse question = questionMapper.findById(userId, id);

        // SQL 已经限制归属，因此“不存在”和“不属于本人”都会查不到。
        if (question == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "题目不存在"
            );
        }

        return question;
    }
}
