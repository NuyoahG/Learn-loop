package com.learnloop.learning;

import com.learnloop.generation.GenerationClient;
import com.learnloop.generation.GenerationException;
import com.learnloop.generation.GenerationProperties;
import com.learnloop.modelsettings.ModelCredentialService;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/** 简要讲解只读库；用户主动请求详解时才调用模型。此服务不会更改作答、掌握度或复习排期。 */
@Service
public class AnswerExplanationService {
    public record Brief(String brief) {}
    public record Detailed(String answer) {}
    private final AnswerExplanationMapper mapper;
    private final GenerationClient model;
    private final ModelCredentialService credentials;
    private final GenerationProperties generation;
    private final LearningProperties learning;
    private final Set<Long> active=ConcurrentHashMap.newKeySet();

    public AnswerExplanationService(AnswerExplanationMapper mapper,GenerationClient model,
            ModelCredentialService credentials,GenerationProperties generation,LearningProperties learning) {
        this.mapper=mapper;this.model=model;this.credentials=credentials;this.generation=generation;this.learning=learning;
    }

    public Brief brief(long user,String kind,String id) {
        var row=requireAnswer(user,kind,id);
        if(row.explanation()!=null && !row.explanation().isBlank()) return new Brief(clip(row.explanation().strip(),600));
        // 人工旧题或已修改原题可能没有可复用解析。回退只陈述冻结答案与考点，不编造选项推理。
        String option=option(row,row.correctAnswer());
        return new Brief("正确选项是 "+row.correctAnswer()+"："+clip(option,180)+"。本题考查“"
                +clip(row.conceptName(),80)+"”："+clip(row.definition(),180));
    }

    public Detailed detailed(long user,String kind,String id) {
        var row=requireAnswer(user,kind,id);
        if(!generation.isEnabled()) throw new GenerationException(503,"MODEL_NOT_CONFIGURED","请先启用模型服务。");
        // 同账号最多一个在途详解，连点或不同标签页并发不会同时触发多次付费调用。
        if(!active.add(user)) throw new LearningException(429,"EXPLANATION_BUSY","正在生成详解，请稍等。");
        try {
            String key=credentials.requireKey(user);
            String answer=model.explain(source(row),"正确选项："+row.correctAnswer(),
                    "请详细解析这道已经提交的题目：说明正确选项为何成立、其他选项的问题，以及容易混淆的知识点。"
                    +"以题干给定前提和资料原文为依据，区分补充前提；依据不足时明确说明，不编造推导。", "DETAILED",key);
            if(answer==null || answer.isBlank() || answer.codePointCount(0,answer.length())>5000
                    || answer.codePoints().anyMatch(cp -> cp==0 || cp>=0xD800 && cp<=0xDFFF)) {
                throw new GenerationException(502,"MODEL_OUTPUT_INVALID","详解内容无效，请稍后重试。");
            }
            return new Detailed(answer.strip());
        } finally {active.remove(user);}
    }

    private AnswerExplanationMapper.Answer requireAnswer(long user,String kind,String id) {
        if(!learning.isEnabled()) throw new LearningException(503,"LEARNING_NOT_ENABLED","学习功能尚未启用。");
        if(user<=0) throw new LearningException(401,"LOGIN_REQUIRED","请先登录。");
        boolean uuid=false;
        try {uuid=id!=null && UUID.fromString(id).toString().equals(id);} catch(IllegalArgumentException ignored) {}
        if(!uuid || !("PRACTICE".equals(kind) || "REVIEW".equals(kind)))
            throw new LearningException(400,"INVALID_EXPLANATION_REQUEST","答题类型或编号无效。");
        var answer="PRACTICE".equals(kind)?mapper.practice(user,id):mapper.review(user,id);
        // 未提交、其他用户和不存在的记录统一为404，不能通过此接口提前探测或读取答案。
        if(answer==null) throw new LearningException(404,"ANSWER_NOT_FOUND","没有找到已提交的答题记录。");
        return answer;
    }

    static String source(AnswerExplanationMapper.Answer row) {
        // 复用既有阅读详解协议（source≤4000、quote必须在source中）；材料来自绑定时的原文快照。
        // 每个字段单独限长并标明节选，保留题干/四个选项/正确与所选答案，不能截断成无效 JSON。
        String source="正确选项："+row.correctAnswer()+"\n所选选项："+row.selected()
                +"\n考点："+clip(row.conceptName(),80)+"\n定义（节选）："+clip(row.definition(),200)
                +"\n题干（节选）："+clip(row.content(),1000)
                +"\nA. "+clip(row.optionA(),200)+"\nB. "+clip(row.optionB(),200)
                +"\nC. "+clip(row.optionC(),200)+"\nD. "+clip(row.optionD(),200)
                +"\n资料原文（节选）："+sourceExcerpt(row.sourceText(),row.sourceQuote());
        // 总预算按Unicode码点裁剪；正确选项在开头，始终可作为内部阅读协议的合法引用。
        return clip(source,4000);
    }
    private static String sourceExcerpt(String text,String quote) {
        if(text==null) return "";
        int at=quote==null || quote.isBlank()?-1:text.indexOf(quote);
        if(at<0) return clip(text,1500);
        // 考题依据可能在4000字正文块的末尾。以发布时已核验的引用定位，再保留前后文，避免只截开头而丢掉依据。
        int total=text.codePointCount(0,text.length());
        int start=Math.max(0,Math.min(text.codePointCount(0,at)-200,total-1500));
        return (start>0?"…":"")+clip(text.substring(text.offsetByCodePoints(0,start)),1500);
    }
    private static String option(AnswerExplanationMapper.Answer row,String letter) {
        return switch(letter) {case "A" -> row.optionA();case "B" -> row.optionB();case "C" -> row.optionC();case "D" -> row.optionD();default -> "";};
    }
    private static String clip(String value,int limit) {
        if(value==null) return "";
        int count=value.codePointCount(0,value.length());
        return count<=limit?value:value.substring(0,value.offsetByCodePoints(0,limit-1))+"…";
    }
}
