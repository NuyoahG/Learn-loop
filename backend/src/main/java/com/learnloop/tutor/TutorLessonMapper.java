package com.learnloop.tutor;

import java.util.List;
import org.apache.ibatis.annotations.*;

/** 辅导记忆和学习算法分开保存：满意度既不是答题证据，也不是掌握概率。 */
@Mapper
public interface TutorLessonMapper {
    record Lesson(String id,Long materialId,String requestHash,String question,String answer,String teaching,
                  String evidenceJson,String traceJson,String feedback,String detail,String createdAt) {}
    String COLUMNS="id,material_id AS materialId,request_hash AS requestHash,question,answer,teaching,"
            +"evidence_json AS evidenceJson,trace_json AS traceJson,feedback,detail,CAST(created_at AS CHAR) AS createdAt";

    @Select("SELECT "+COLUMNS+" FROM tutor_lessons WHERE user_id=#{user} AND id=#{id}")
    Lesson find(@Param("user") long user,@Param("id") String id);

    @Select("SELECT "+COLUMNS+" FROM tutor_lessons WHERE user_id=#{user} "
            +"AND (#{material} IS NULL OR material_id=#{material}) ORDER BY created_at DESC,id DESC LIMIT 4")
    List<Lesson> recent(@Param("user") long user,@Param("material") Long material);

    // 纠正和未解决反馈独立于最近对话窗口；同一资料旧反馈不会因一次普通聊天马上被挤掉。
    // shortcut: 首版保留最近30天的4条显式反馈；升级长期记忆时增加可审阅的归纳/撤销界面。
    @Select("SELECT "+COLUMNS+" FROM tutor_lessons WHERE user_id=#{user} "
            +"AND (#{material} IS NULL OR material_id=#{material}) AND feedback<>'PENDING' "
            +"AND updated_at>=UTC_TIMESTAMP(6)-INTERVAL 30 DAY ORDER BY updated_at DESC,id DESC LIMIT 4")
    List<Lesson> feedbackMemory(@Param("user") long user,@Param("material") Long material);

    @Insert("""
        INSERT INTO tutor_lessons(user_id,id,material_id,request_hash,question,answer,teaching,evidence_json,trace_json,
          feedback,detail,created_at,updated_at)
        VALUES(#{user},#{row.id},#{row.materialId},#{row.requestHash},#{row.question},#{row.answer},#{row.teaching},
          #{row.evidenceJson},#{row.traceJson},'PENDING','',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
        """)
    void insert(@Param("user") long user,@Param("row") Lesson row);

    @Update("""
        UPDATE tutor_lessons SET feedback=#{feedback},detail=#{detail},updated_at=UTC_TIMESTAMP(6)
        WHERE user_id=#{user} AND id=#{id}
        """)
    int feedback(@Param("user") long user,@Param("id") String id,@Param("feedback") String feedback,@Param("detail") String detail);
}
