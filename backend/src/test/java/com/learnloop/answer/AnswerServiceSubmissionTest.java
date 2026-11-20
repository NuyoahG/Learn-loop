package com.learnloop.answer;

import com.learnloop.question.QuestionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 使用模拟 Mapper 验证提交业务，不连接 MySQL。
 * 验证判分和快照仍正确，以及查询无权访问的题目时不会保存记录。
 */
class AnswerServiceSubmissionTest {

    private static final Long USER_ID = 42L;
    private static final Long OTHER_USER_ID = 43L;
    private static final Long QUESTION_ID = 101L;

    private QuestionMapper questionMapper;
    private AnswerMapper answerMapper;
    private AnswerService answerService;

    @BeforeEach
    void setUp() {
        questionMapper = mock(QuestionMapper.class);
        answerMapper = mock(AnswerMapper.class);
        answerService = new AnswerService(questionMapper, answerMapper);

        // 模拟题目 101 属于用户 42，正确选项为 B。
        // 对其他用户的相同查询没有设置返回值，Mockito 默认返回 null。
        when(questionMapper.findCorrectAnswerById(USER_ID, QUESTION_ID))
                .thenReturn("B");

        // 模拟插入后由 MyBatis 回填数据库主键。
        when(answerMapper.insert(any(AnswerRecord.class))).thenAnswer(invocation -> {
            AnswerRecord savedRecord = invocation.getArgument(0);
            savedRecord.setId(123L);
            return 1;
        });
    }

    @Test
    void wrongAnswerKeepsSnapshotAndCurrentUserOwnership() {
        SubmitAnswerResponse response = answerService.submit(
                USER_ID,
                new SubmitAnswerRequest(QUESTION_ID, "C")
        );

        assertEquals(Long.valueOf(123L), response.recordId());
        assertFalse(response.correct());
        assertEquals("B", response.correctOption());

        // 捕获实际准备保存的对象，检查身份、题号和判分依据。
        ArgumentCaptor<AnswerRecord> captor = ArgumentCaptor.forClass(AnswerRecord.class);
        verify(answerMapper).insert(captor.capture());
        AnswerRecord savedRecord = captor.getValue();

        assertEquals(USER_ID, savedRecord.getUserId());
        assertEquals(QUESTION_ID, savedRecord.getQuestionId());
        assertEquals("C", savedRecord.getSelectedOption());
        assertEquals(response.correctOption(), savedRecord.getCorrectOptionSnapshot());
        verify(questionMapper).findCorrectAnswerById(USER_ID, QUESTION_ID);
    }

    @Test
    void correctAnswerKeepsTheSuccessfulGradingResult() {
        SubmitAnswerResponse response = answerService.submit(
                USER_ID,
                new SubmitAnswerRequest(QUESTION_ID, "B")
        );

        assertEquals(Long.valueOf(123L), response.recordId());
        assertTrue(response.correct());
        assertEquals("B", response.correctOption());
    }

    @Test
    void anotherUsersQuestionCannotCreateAnAnswerRecord() {
        // 模拟用户 43 尝试回答属于用户 42 的题目。
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> answerService.submit(
                        OTHER_USER_ID,
                        new SubmitAnswerRequest(QUESTION_ID, "B")
                )
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        verify(questionMapper).findCorrectAnswerById(OTHER_USER_ID, QUESTION_ID);

        // 关键断言：被拒绝的请求不能产生答题记录。
        verify(answerMapper, never()).insert(any(AnswerRecord.class));
    }
}
