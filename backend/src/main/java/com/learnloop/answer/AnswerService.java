package com.learnloop.answer;

import com.learnloop.question.QuestionMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class AnswerService {

    private final QuestionMapper questionMapper;
    private final AnswerMapper answerMapper;

    public AnswerService(
            QuestionMapper questionMapper,
            AnswerMapper answerMapper
    ) {
        this.questionMapper = questionMapper;
        this.answerMapper = answerMapper;
    }

    public SubmitAnswerResponse submit(
            Long userId,
            SubmitAnswerRequest request
    ) {
        requireUserId(userId);

        if (request == null
                || request.questionId() == null
                || request.questionId() <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "题目编号必须是正整数"
            );
        }

        String selectedOption = request.selectedOption();
        if (selectedOption == null || !selectedOption.matches("[A-D]")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "选项必须是大写的 A、B、C、D"
            );
        }

        // 查询正确答案的同时检查题目归属。
        // 不能先按题号读出其他人的答案，再只给新记录补上当前用户编号。
        String correctAnswer = questionMapper.findCorrectAnswerById(
                userId,
                request.questionId()
        );

        if (correctAnswer == null) {
            // 对方的题目和不存在的题目都在这里结束，后面不会插入记录。
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "题目不存在"
            );
        }

        if (!correctAnswer.matches("[A-D]")) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "题目的正确答案配置有误"
            );
        }

        // 单选题仍由 Java 做确定性判分。
        boolean correct = correctAnswer.equals(selectedOption);

        // 同一个 userId 用于检查题目归属和保存答题归属。
        // 构造参数第一个是用户编号，第二个才是题目编号。
        AnswerRecord record = new AnswerRecord(
                userId,
                request.questionId(),
                selectedOption,
                correctAnswer,
                correct
        );

        answerMapper.insert(record);

        // 只有保存成功后才返回；返回答案与保存的答案快照一致。
        return new SubmitAnswerResponse(record.getId(), correct, correctAnswer);
    }

    public AnswerRecordDetail getById(Long userId, Long recordId) {
        requireUserId(userId);

        if (recordId == null || recordId <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "记录编号必须是正整数"
            );
        }

        AnswerRecordDetail result = answerMapper.findById(userId, recordId);

        if (result == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "答题记录不存在"
            );
        }

        return result;
    }

    public AnswerPageResponse getPage(
            Long userId,
            int page,
            int size,
            Boolean correct,
            String sort
    ) {
        requireUserId(userId);

        if (page < 1) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "page 必须大于等于 1"
            );
        }

        if (size < 1 || size > 100) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "size 必须在 1 到 100 之间"
            );
        }

        if (!"asc".equals(sort) && !"desc".equals(sort)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "sort 必须是 asc 或 desc"
            );
        }

        // 先转为 long，再做乘法，避免 int 乘法溢出。
        long offset = ((long) page - 1) * size;

        // 列表和 COUNT 必须传同一个用户编号、同一个对错筛选条件。
        long total = answerMapper.countByCorrect(userId, correct);
        List<AnswerRecordResponse> records = answerMapper.findPage(
                userId,
                size,
                offset,
                correct,
                sort
        );

        long totalPages = total / size + (total % size == 0 ? 0 : 1);

        // 超过末页仍返回 200 和空列表；没有记录时总条数、总页数均为 0。
        return new AnswerPageResponse(page, size, total, totalPages, records);
    }

    /**
     * 三个业务入口共用身份检查。
     * 即使以后从其他服务调用，也不允许缺少用户编号时执行查询。
     */
    private void requireUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "请先登录"
            );
        }
    }
}
