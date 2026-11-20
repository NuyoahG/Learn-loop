package com.learnloop.review;

import com.learnloop.generation.GenerationClient;
import com.learnloop.generation.GenerationException;
import com.learnloop.generation.GenerationService;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReviewService {
    public record Answer(String attemptId, String selected, String correctAnswer, String explanation) {}
    public record Result(Instant dueAt, int rating) {}

    private final ReviewMapper mapper;
    private final GenerationService generation;
    private final GenerationClient scheduler;
    private final TransactionTemplate tx;

    ReviewService(ReviewMapper mapper, GenerationService generation, GenerationClient scheduler,
                  PlatformTransactionManager transactions) {
        this.mapper = mapper;
        this.generation = generation;
        this.scheduler = scheduler;
        this.tx = new TransactionTemplate(transactions);
    }

    public ReviewMapper.Due today(long user) {
        generation.restoreReviewCards(user);
        return mapper.next(user, now());
    }

    public Answer answer(long user, String topic, String version, String selected, LocalDateTime priorDue) {
        uuid(topic);
        uuid(version);
        if (selected == null || !selected.matches("[A-D]")) throw invalid();
        return tx.execute(status -> {
            var card = mapper.lockCard(user, topic);
            checkDue(card);
            if (!card.dueAt().equals(priorDue)) throw stale();
            var previous = mapper.current(user, card);
            if (previous != null) {
                // 同一轮刷新后仍返回原来的答案；避免重做导致复习评分对应错题。
                if (!previous.versionId().equals(version)) throw stale();
                var question = requireQuestion(user, topic, version);
                return new Answer(previous.id(), previous.selected(), question.correctAnswer(), question.explanation());
            }
            var question = requireQuestion(user, topic, version);
            String id = UUID.randomUUID().toString();
            mapper.reveal(id, user, card, version, selected);
            return new Answer(id, selected, question.correctAnswer(), question.explanation());
        });
    }

    public Result rate(long user, String id, int rating) {
        uuid(id);
        if (rating < 1 || rating > 4) throw invalid();
        var initial = requireAttempt(user, id);
        if (initial.rating() != null) return replay(initial, rating);
        LocalDateTime reviewed = now();
        GenerationClient.ReviewSchedule scheduled;
        try {
            // 算法调用放在事务外，避免网络等待期间持有复习卡行锁。
            scheduled = scheduler.scheduleReview(mapper.previousState(user, initial), rating, utc(reviewed));
        } catch (GenerationException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "排期暂未完成，请重试本次反馈。");
        }
        LocalDateTime due = LocalDateTime.ofInstant(scheduled.dueAt(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        return tx.execute(status -> {
            var card = mapper.lockCard(user, initial.topicId());
            var current = requireAttempt(user, id);
            if (current.rating() != null) return replay(current, rating);
            checkDue(card);
            if (!card.dueAt().equals(current.priorDue()) || !due.isAfter(reviewed) || !due.isAfter(card.dueAt())) throw stale();
            if (mapper.rate(user, id, rating, reviewed, due, scheduled.state()) != 1
                    || mapper.schedule(user, card, due) != 1) throw stale();
            return new Result(utc(due), rating);
        });
    }

    private ReviewMapper.Question requireQuestion(long user, String topic, String version) {
        var q = mapper.question(user, topic, version);
        if (q == null) throw missing();
        return q;
    }

    private ReviewMapper.Attempt requireAttempt(long user, String id) {
        var a = mapper.attempt(user, id);
        if (a == null) throw missing();
        return a;
    }

    private static Result replay(ReviewMapper.Attempt attempt, int rating) {
        if (attempt.rating() != rating) throw stale();
        return new Result(utc(attempt.nextDue()), rating);
    }

    private static void checkDue(ReviewMapper.Card card) {
        if (card == null) throw missing();
        if (card.dueAt().isAfter(now())) throw stale();
    }

    private static Instant utc(LocalDateTime date) { return date.toInstant(ZoneOffset.UTC); }
    private static LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS); }
    private static void uuid(String value) {
        if (value == null || !value.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")) throw invalid();
    }
    private static ResponseStatusException missing() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到复习卡。"); }
    private static ResponseStatusException stale() { return new ResponseStatusException(HttpStatus.CONFLICT, "复习状态已变化。"); }
    private static ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.BAD_REQUEST, "复习参数无效。"); }
}
