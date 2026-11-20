package com.learnloop.tutor;

import com.learnloop.generation.GenerationService;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

/** 聊天和规划共用一个只读入口；学习状态仍以原算法与业务表为准。 */
@Service
public class LearningSummaryService {
    private final LearningSummaryMapper evidence;
    private final TutorMapper tutor;
    private final GenerationService generation;
    private final JsonMapper json = JsonMapper.builder().build();

    LearningSummaryService(LearningSummaryMapper evidence, TutorMapper tutor, GenerationService generation) {
        this.evidence=evidence; this.tutor=tutor; this.generation=generation;
    }

    public record Summary(Long materialId,List<LearningSummaryMapper.Attempt> attempts,
                          List<LearningSummaryMapper.Reading> reading,List<TutorMapper.DueTopic> dueTopics,
                          List<?> groupMastery,List<String> correctedObservationKeys) {}

    @Transactional(readOnly=true)
    public Summary load(long user,Long material) {
        if(material!=null && !evidence.owns(user,material))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"没有找到这份资料。");
        var attempts=evidence.attempts(user,material);
        var reading=evidence.reading(user,material);
        var due=tutor.due(user,material);
        // 未指定资料时，优先沿当前最近的学习记录取知识组；不把不同资料的同名考点合并。
        Long scope=material;
        if(scope==null && !attempts.isEmpty()) scope=attempts.getFirst().materialId();
        if(scope==null && !reading.isEmpty()) scope=reading.getFirst().materialId();
        if(scope==null && !due.isEmpty()) scope=due.getFirst().materialId();
        return new Summary(scope,List.copyOf(attempts),List.copyOf(reading),List.copyOf(due),
                scope==null?List.of():generation.tutorMastery(user,scope),tutor.corrected(user).stream().sorted().limit(30).toList());
    }

    /** 按完整记录裁剪，不能截断 JSON。该摘要是限量证据窗口，不代表全部历史或新的掌握度结论。 */
    String context(Summary s,boolean planning) {
        var attempts=new ArrayList<Object>();
        for(var a:s.attempts()) {
            if(!planning) attempts.add(a);
            else {
                var item=new LinkedHashMap<String,Object>();
                item.put("kind",a.kind()); item.put("id",a.id()); item.put("familyId",a.familyId());
                item.put("materialId",a.materialId()); item.put("topic",a.topicName()); item.put("correct",a.correct());
                item.put("assisted",a.assisted()); item.put("reason",a.reason()); item.put("occurredAt",a.occurredAt());
                attempts.add(item);
            }
        }
        // 规划只需要困惑信号；题干、答案与自由笔记不进入规划原因，防止提前泄露候选题答案。
        var reading=new ArrayList<Object>();
        for(var r:s.reading()) reading.add(planning?Map.of("kind",r.kind(),"id",r.id(),
                "materialId",r.materialId(),"intent",r.intent()):r);
        var value=new LinkedHashMap<String,Object>();
        value.put("schemaVersion","learning-summary-v1"); value.put("masteryMaterialId",s.materialId());
        value.put("groupMastery",s.groupMastery()); value.put("dueTopics",s.dueTopics());
        value.put("correctedObservationKeys",s.correctedObservationKeys());
        value.put("attempts",attempts); value.put("reading",reading);
        value.put("evidencePolicy","近30天已提交练习与已评分复习；复习和重复题族不增加BKT证据；高亮不等于不会；时间为UTC。");
        value.put("truncated",false);
        int budget=planning?5500:10000;
        String encoded=json.writeValueAsString(value);
        while(encoded.length()>budget && (!attempts.isEmpty() || !reading.isEmpty())) {
            if(attempts.size()>=reading.size() && !attempts.isEmpty()) attempts.removeLast(); else reading.removeLast();
            value.put("truncated",true);
            encoded=json.writeValueAsString(value);
        }
        return encoded;
    }
}
