package com.learnloop.tutor;

import com.learnloop.generation.GenerationClient;
import com.learnloop.generation.GenerationException;
import com.learnloop.modelsettings.ModelCredentialService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** 一次辅导：只读摘要→受限 Agent→验证来源→保存回答→用户反馈。模型调用不占数据库事务。 */
@Service
public class TutorCoachService {
    private final LearningSummaryService summaries;
    private final LearningSummaryMapper summaryMapper;
    private final TutorRetrievalService retrieval;
    private final TutorLessonMapper lessons;
    private final GenerationClient model;
    private final ModelCredentialService credentials;
    private final JsonMapper json=JsonMapper.builder().build();
    private final Set<Long> active=ConcurrentHashMap.newKeySet();
    public record Turn(String role,String text) {}
    public record Chat(String message,Long materialId,List<Turn> history,String requestId) {}
    public record Feedback(String status,String detail) {}
    public record Evidence(String label,Long materialId,String kind,String id) {}
    public record Reply(String id,String question,String answer,String teaching,List<Evidence> evidence,
                        List<TutorRetrievalService.Trace> trace,String feedback,String detail,Long materialId) {}

    TutorCoachService(LearningSummaryService summaries,LearningSummaryMapper summaryMapper,TutorRetrievalService retrieval,
                      TutorLessonMapper lessons,GenerationClient model,ModelCredentialService credentials) {
        this.summaries=summaries;this.summaryMapper=summaryMapper;this.retrieval=retrieval;
        this.lessons=lessons;this.model=model;this.credentials=credentials;
    }

    public List<Reply> recent(long user,Long material) {
        requireMaterial(user,material);
        return lessons.recent(user,material).stream().map(this::view).toList();
    }
    public Reply feedback(long user,String id,Feedback feedback) {
        requireId(id);
        if(feedback==null || feedback.status()==null || !Set.of("HELPFUL","UNRESOLVED","INCORRECT","PENDING").contains(feedback.status()))
            throw bad("请选择反馈。");
        String detail=feedback.detail()==null?"":feedback.detail().strip();
        if(detail.length()>300 || !safeText(detail)) throw bad("补充说明最多300字。");
        var lesson=lessons.find(user,id);
        if(lesson==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"没有找到这次辅导。");
        // 单条状态覆盖，重复点击不累加次数。这里只写辅导反馈表，绝不更新 BKT、FSRS 或答案。
        lessons.feedback(user,id,feedback.status(),"PENDING".equals(feedback.status())?"":detail);
        return view(lessons.find(user,id));
    }

    public Reply chat(long user,Chat input) {
        if(input==null || input.message()==null || input.message().isBlank() || input.message().length()>500 || !safeText(input.message()))
            throw bad("请填写1到500字的问题。");
        requireMaterial(user,input.materialId());
        String id=input.requestId()==null?UUID.randomUUID().toString():input.requestId(); requireId(id);
        String message=input.message().strip();
        String hash=digest(json.writeValueAsString(Arrays.asList(message,input.materialId())));
        // 前端重试保留 requestId；已成功的请求直接读库，不再次调用付费模型。
        var existing=lessons.find(user,id);
        if(existing!=null) return replay(existing,hash);
        if(!active.add(user)) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"小栗正在回答，请稍等。");
        String token=null;
        try {
            existing=lessons.find(user,id);
            if(existing!=null) return replay(existing,hash);
            var summary=summaries.load(user,input.materialId());
            List<TutorRetrievalService.Item> initial=new ArrayList<>();
            // 仅给模型索引与状态；题干、答案、完整笔记在它选择查询后才发送。
            summary.attempts().forEach(a -> initial.add(new TutorRetrievalService.Item(a.kind()+":"+a.id(),
                a.materialName()+" · "+(a.topicName()==null?"作答记录":a.topicName()),a.materialId(),a.kind(),a.id(),
                Map.of("correct",a.correct(),"assisted",a.assisted(),"occurredAt",a.occurredAt()))));
            summary.reading().forEach(r -> initial.add(new TutorRetrievalService.Item(r.kind()+":"+r.id(),
                r.materialName()+" · "+r.quote(),r.materialId(),r.kind(),r.id(),Map.of("intent",r.intent()))));
            var context=new LinkedHashMap<String,Object>();
            context.put("learningSummary",json.readTree(summaries.context(summary,true)));
            context.put("evidence",initial);
            // 只用服务端已保存的对话。客户端传来的 assistant 文本不能伪造老师曾经说过的话。
            context.put("recentLessons",lessons.recent(user,input.materialId()).stream().limit(2).map(this::memory).toList());
            context.put("feedbackMemory",lessons.feedbackMemory(user,input.materialId()).stream().map(this::memory).toList());
            String encoded=json.writeValueAsString(context);
            if(encoded.length()>23000) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"学习摘要过长，请选择一份资料后再问。");
            String key=credentials.requireKey(user);
            token=retrieval.open(user,input.materialId(),initial);
            var answer=model.tutorCoach(message,encoded,token,key);
            List<Evidence> evidence=retrieval.cited(token,answer.citations()).stream().map(row ->
                new Evidence(row.label(),row.materialId(),row.kind(),row.id())).toList();
            // Python 轨迹只用于契约校验；返回网页和存库的轨迹来自 Java 真实查询。
            var trace=retrieval.trace(token);
            if(!trace.equals(answer.trace())) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"辅导查询轨迹不一致。");
            var row=new TutorLessonMapper.Lesson(id,input.materialId(),hash,message,answer.answer(),answer.teaching(),
                json.writeValueAsString(evidence),json.writeValueAsString(trace),"PENDING","",null);
            lessons.insert(user,row);
            return view(row);
        } catch(GenerationException failure) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"小栗暂时无法回答，请稍后重试。");
        } finally {
            if(token!=null) retrieval.close(token);
            active.remove(user);
        }
    }
    private Map<String,Object> memory(TutorLessonMapper.Lesson row) {
        return Map.of("question",clip(row.question(),300),"answer",clip(row.answer(),650),"teaching",row.teaching(),
                "feedback",row.feedback(),"detail",row.detail(),"createdAt",row.createdAt()==null?"":row.createdAt());
    }
    private Reply replay(TutorLessonMapper.Lesson row,String hash) {
        if(!row.requestHash().equals(hash)) throw new ResponseStatusException(HttpStatus.CONFLICT,"请求编号已用于另一个问题。");
        return view(row);
    }
    private Reply view(TutorLessonMapper.Lesson row) {
        return new Reply(row.id(),row.question(),row.answer(),row.teaching(),
            List.of(json.readValue(row.evidenceJson(),Evidence[].class)),
            List.of(json.readValue(row.traceJson(),TutorRetrievalService.Trace[].class)),row.feedback(),row.detail(),row.materialId());
    }
    private void requireMaterial(long user,Long material) {
        if(material!=null && (material<=0 || !summaryMapper.owns(user,material)))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"没有找到这份资料。");
    }
    private static void requireId(String id) {
        if(id==null || !id.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")) throw bad("辅导编号无效。");
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static String clip(String value,int length) {
        return value.substring(0,value.offsetByCodePoints(0,Math.min(length,value.codePointCount(0,value.length()))));
    }
    private static boolean safeText(String value) { return value.codePoints().noneMatch(cp -> cp==0 || cp>=0xD800 && cp<=0xDFFF); }
    private static ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
}
