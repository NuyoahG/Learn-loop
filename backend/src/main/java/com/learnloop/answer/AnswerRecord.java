package com.learnloop.answer;

/**
 * 用于向 answer_records 表写入一条答题记录。
 * userId 由当前登录身份提供，不能取自客户端提交的数据。
 */
public class AnswerRecord {

    // id 在插入前为空，插入成功后由 MyBatis 回填，因此保留 setter。
    private Long id;

    private final Long userId;
    private final Long questionId;
    private final String selectedOption;
    private final String correctOptionSnapshot;
    private final boolean correct;

    public AnswerRecord(
            Long userId,
            Long questionId,
            String selectedOption,
            String correctOptionSnapshot,
            boolean correct
    ) {
        this.userId = userId;
        this.questionId = questionId;
        this.selectedOption = selectedOption;
        this.correctOptionSnapshot = correctOptionSnapshot;
        this.correct = correct;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    // MyBatis 使用这个 getter 读取 INSERT 中 #{userId} 的值。
    public Long getUserId() {
        return userId;
    }

    public Long getQuestionId() {
        return questionId;
    }

    public String getSelectedOption() {
        return selectedOption;
    }

    public String getCorrectOptionSnapshot() {
        return correctOptionSnapshot;
    }

    public boolean isCorrect() {
        return correct;
    }
}
