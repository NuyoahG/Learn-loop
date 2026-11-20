package com.learnloop.tutor;

import java.util.List;
import org.apache.ibatis.annotations.*;

/** 按需查询的只读投影。查询词是绑定参数，不拼 SQL，不接受模型指定用户或任意资料 ID。 */
@Mapper
public interface TutorRetrievalMapper {
    record Source(String ref, Long materialId, String label, String locator, String content) {}
    record Note(String ref, Long materialId, String label, String quote, String question, String content, String intent) {}

    // 用 LOCATE 做字面关键词检索，% 和 _ 不是通配符。截取命中附近而非固定取开头，减少上下文成本。
    @Select("""
        SELECT CONCAT('SOURCE:',b.id) AS ref,m.id AS materialId,m.original_name AS label,
          b.source_locator AS locator,
          SUBSTRING(b.text_content,GREATEST(1,LOCATE(#{query},b.text_content)-250),1800) AS content
        FROM material_blocks b JOIN materials m ON m.id=b.material_id
        WHERE m.user_id=#{user} AND m.parse_status='PARSED'
          AND (#{material} IS NULL OR m.id=#{material}) AND LOCATE(#{query},b.text_content)>0
        ORDER BY m.updated_at DESC,m.id DESC,b.block_no LIMIT 3
        """)
    List<Source> sources(@Param("user") long user,@Param("material") Long material,@Param("query") String query);

    // 原件批注与同步笔记合为一条。删除的 PDF 批注不通过关联笔记重新出现；旧文字高亮不冒充当前定位。
    @Select("""
        SELECT ref,materialId,label,SUBSTRING(quote,GREATEST(1,LOCATE(#{query},quote)-100),500) AS quote,
          LEFT(question,200) AS question,SUBSTRING(content,GREATEST(1,LOCATE(#{query},content)-200),1200) AS content,intent
        FROM (
          SELECT CONCAT('PDF_ANNOTATION:',p.id) AS ref,p.material_id AS materialId,m.original_name AS label,
            p.quote,'' AS question,COALESCE(n.content,'') AS content,'HIGHLIGHT' AS intent,p.updated_at AS updated
          FROM pdf_annotations p JOIN materials m ON m.id=p.material_id AND m.user_id=p.user_id
          LEFT JOIN reading_notes n ON n.id=p.note_id AND n.user_id=p.user_id AND n.material_id=p.material_id
          WHERE p.user_id=#{user} AND p.deleted=0 AND (#{material} IS NULL OR p.material_id=#{material})
          UNION ALL
          SELECT CONCAT('READING_NOTE:',n.id),n.material_id,m.original_name,n.quote,n.question,n.content,
            COALESCE(s.intent,'HIGHLIGHT'),n.updated_at
          FROM reading_notes n JOIN materials m ON m.id=n.material_id AND m.user_id=n.user_id
          LEFT JOIN reading_highlights h ON h.id=n.id AND h.user_id=n.user_id AND h.material_id=n.material_id
            AND h.parse_attempt=m.parse_attempt AND m.parse_status='PARSED'
          LEFT JOIN reading_highlight_study s ON s.highlight_id=h.id
          WHERE n.user_id=#{user} AND (#{material} IS NULL OR n.material_id=#{material})
            AND NOT EXISTS(SELECT 1 FROM pdf_annotations p WHERE p.user_id=n.user_id AND p.note_id=n.id)
          UNION ALL
          SELECT CONCAT('MARK:',h.id),h.material_id,m.original_name,h.quote,h.question,'',COALESCE(s.intent,'HIGHLIGHT'),h.created_at
          FROM reading_highlights h JOIN materials m ON m.id=h.material_id AND m.user_id=h.user_id
            AND m.parse_status='PARSED' AND m.parse_attempt=h.parse_attempt
          LEFT JOIN reading_highlight_study s ON s.highlight_id=h.id
          WHERE h.user_id=#{user} AND (#{material} IS NULL OR h.material_id=#{material})
            AND NOT EXISTS(SELECT 1 FROM reading_notes n WHERE n.user_id=h.user_id AND n.id=h.id)
          UNION ALL
          SELECT CONCAT('NOTE:',n.question_version_id),c.material_id,m.original_name,q.content,'',n.content,'NOTE',n.updated_at
          FROM question_notes n JOIN learning_questions q ON q.id=n.question_version_id AND q.user_id=n.user_id
          JOIN learning_concepts c ON c.id=q.concept_id AND c.user_id=q.user_id
          JOIN materials m ON m.id=c.material_id AND m.user_id=c.user_id
          WHERE n.user_id=#{user} AND (#{material} IS NULL OR c.material_id=#{material})
        ) notes
        WHERE LOCATE(#{query},CONCAT(quote,' ',question,' ',content))>0
        ORDER BY updated DESC,ref LIMIT 3
        """)
    List<Note> notes(@Param("user") long user,@Param("material") Long material,@Param("query") String query);
}
