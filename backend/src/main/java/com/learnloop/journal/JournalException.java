package com.learnloop.journal;

final class JournalException extends RuntimeException {
    final int status;
    final String code;

    JournalException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    static JournalException invalid(String message) { return new JournalException(400, "INVALID_JOURNAL_REQUEST", message); }
    static JournalException stale() { return new JournalException(409, "READING_SOURCE_CHANGED", "正文版本或选区已变化，请刷新阅读页后重新选择。"); }
    static JournalException missing() { return new JournalException(404, "JOURNAL_NOT_FOUND", "找不到可访问的资料或题目。"); }
}
