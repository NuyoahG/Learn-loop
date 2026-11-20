package com.learnloop.journal;

import static com.learnloop.journal.JournalApi.*;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface JournalMapper {
    String NOTE = """
        SELECT n.question_version_id AS questionVersionId,n.content,n.revision,n.updated_at AS updatedAt,
               q.content AS question,m.id AS materialId,m.original_name AS materialName,
               NULL AS readingNoteId,NULL AS quote,NULL AS annotationId
        FROM question_notes n JOIN learning_questions q ON q.user_id=n.user_id AND q.id=n.question_version_id
        JOIN learning_concepts c ON c.user_id=q.user_id AND c.id=q.concept_id
        JOIN materials m ON m.user_id=c.user_id AND m.id=c.material_id
        WHERE n.user_id=#{user}
        """;
    String NOTE_FILTER = """
        AND (#{query}='' OR LOCATE(#{query}, n.content)>0 OR LOCATE(#{query}, q.content)>0
             OR LOCATE(#{query}, m.original_name)>0)
        """;
    String READING_NOTE_FIELDS = """
        SELECT NULL AS questionVersionId,r.content,r.revision,r.updated_at AS updatedAt,
               r.question,m.id AS materialId,m.original_name AS materialName,r.id AS readingNoteId,r.quote,
        """;
    String READING_NOTE_SOURCE = """
        FROM reading_notes r JOIN materials m ON m.id=r.material_id AND m.user_id=r.user_id
        WHERE r.user_id=#{user}
        """;
    String READING_NOTE = READING_NOTE_FIELDS + " NULL AS annotationId " + READING_NOTE_SOURCE;
    String HIGHLIGHT = """
        SELECT h.id,h.block_id AS blockId,h.start_offset AS `start`,h.end_offset AS `end`,
               h.quote,h.question,h.answer,h.detail,h.color,s.intent,t.id AS topicId,t.name AS topicName
        FROM reading_highlights h
        LEFT JOIN reading_highlight_study s ON s.highlight_id=h.id
        LEFT JOIN adaptive_topics t ON t.id=s.topic_id AND t.user_id=h.user_id AND t.material_id=h.material_id
        """;

    @Select(NOTE + " AND n.question_version_id=#{version}")
    Note note(@Param("user") long user, @Param("version") String version);

    @Select(NOTE + NOTE_FILTER + " ORDER BY n.updated_at DESC,n.question_version_id LIMIT #{limit} OFFSET #{offset}")
    List<Note> notes(@Param("user") long user, @Param("query") String query,
                     @Param("limit") int limit, @Param("offset") long offset);

    @Insert("""
        INSERT INTO question_notes (user_id,question_version_id,content,revision,updated_at)
        VALUES (#{user},#{version},#{content},#{revision},#{updatedAt})
        ON DUPLICATE KEY UPDATE content=#{content},revision=#{revision},updated_at=#{updatedAt}
        """)
    void saveNote(@Param("user") long user, @Param("version") String version,
                  @Param("content") String content, @Param("revision") String revision,
                  @Param("updatedAt") LocalDateTime updatedAt);

    @Select("""
        SELECT EXISTS(SELECT 1 FROM information_schema.tables
          WHERE table_schema=DATABASE() AND table_name='reading_notes' AND table_type='BASE TABLE')
        """)
    boolean readingNotesReady();

    @Select(NOTE + NOTE_FILTER + " UNION ALL " + READING_NOTE + """
        AND (#{query}='' OR LOCATE(#{query}, r.content)>0 OR LOCATE(#{query}, r.question)>0
             OR LOCATE(#{query}, r.quote)>0 OR LOCATE(#{query}, m.original_name)>0)
        ORDER BY updatedAt DESC,questionVersionId,readingNoteId
        LIMIT #{limit} OFFSET #{offset}
        """)
    List<Note> allNotes(@Param("user") long user, @Param("query") String query,
                        @Param("limit") int limit, @Param("offset") long offset);

    @Select(READING_NOTE + " AND r.id=#{id}")
    Note readingNote(@Param("user") long user, @Param("id") String id);

    String PDF_READING_NOTE = READING_NOTE_FIELDS + """
        (SELECT pa.id FROM pdf_annotations pa
          WHERE pa.user_id=r.user_id AND pa.material_id=r.material_id AND pa.note_id=r.id AND pa.deleted=0
          LIMIT 1) AS annotationId
        """ + READING_NOTE_SOURCE;

    @Select(NOTE + NOTE_FILTER + " UNION ALL " + PDF_READING_NOTE + """
        AND (#{query}='' OR LOCATE(#{query}, r.content)>0 OR LOCATE(#{query}, r.question)>0
             OR LOCATE(#{query}, r.quote)>0 OR LOCATE(#{query}, m.original_name)>0)
        ORDER BY updatedAt DESC,questionVersionId,readingNoteId
        LIMIT #{limit} OFFSET #{offset}
        """)
    List<Note> allNotesWithAnnotations(@Param("user") long user, @Param("query") String query,
                                       @Param("limit") int limit, @Param("offset") long offset);

    @Select(PDF_READING_NOTE + " AND r.id=#{id}")
    Note readingNoteWithAnnotation(@Param("user") long user, @Param("id") String id);

    @Select("""
        SELECT EXISTS(SELECT 1 FROM information_schema.tables
          WHERE table_schema=DATABASE() AND table_name='pdf_annotations' AND table_type='BASE TABLE')
        """)
    boolean pdfAnnotationsReady();

    @Insert("""
        INSERT INTO reading_notes (id,user_id,material_id,quote,question,content,revision,updated_at)
        SELECT id,user_id,material_id,quote,COALESCE(NULLIF(question,''),'解释所选原文'),answer,#{revision},#{updatedAt}
        FROM reading_highlights WHERE id=#{id} AND user_id=#{user}
        """)
    int copyHighlightToNote(@Param("user") long user, @Param("id") String id,
                            @Param("revision") String revision, @Param("updatedAt") LocalDateTime updatedAt);

    @Update("""
        UPDATE reading_notes SET content=#{content},revision=#{revision},updated_at=#{updatedAt}
        WHERE id=#{id} AND user_id=#{user}
        """)
    void updateReadingNote(@Param("user") long user, @Param("id") String id, @Param("content") String content,
                           @Param("revision") String revision, @Param("updatedAt") LocalDateTime updatedAt);

    @Delete("DELETE FROM reading_notes WHERE user_id=#{user} AND id=#{id} AND revision=#{revision}")
    int deleteReadingNote(@Param("user") long user, @Param("id") String id, @Param("revision") String revision);

    @Delete("DELETE FROM question_notes WHERE user_id=#{user} AND question_version_id=#{version} AND revision=#{revision}")
    int deleteNote(@Param("user") long user, @Param("version") String version, @Param("revision") String revision);

    @Select(HIGHLIGHT + """
        JOIN material_blocks b ON b.id=h.block_id AND b.material_id=h.material_id
        WHERE h.user_id=#{user} AND h.material_id=#{material} AND h.parse_attempt=#{attempt}
              AND b.block_no BETWEEN #{first} AND #{last}
        ORDER BY b.block_no,h.start_offset
        """)
    List<Highlight> highlights(@Param("user") long user, @Param("material") long material,
                               @Param("attempt") long attempt, @Param("first") long first, @Param("last") long last);

    @Select(HIGHLIGHT + " WHERE h.user_id=#{user} AND h.id=#{id}")
    Highlight highlight(@Param("user") long user, @Param("id") String id);

    @Select("""
        SELECT EXISTS(SELECT 1 FROM reading_highlights
            WHERE user_id=#{user} AND material_id=#{a.materialId} AND parse_attempt=#{a.parseAttempt}
              AND block_id=#{a.blockId} AND start_offset < #{a.end} AND end_offset > #{a.start})
        """)
    boolean overlaps(@Param("user") long user, @Param("a") Anchor anchor);

    @Insert("""
        INSERT INTO reading_highlights
            (id,user_id,material_id,parse_attempt,block_id,start_offset,end_offset,quote,question,answer,detail,color,created_at)
        VALUES (#{d.receipt},#{user},#{d.anchor.materialId},#{d.anchor.parseAttempt},#{d.anchor.blockId},
                #{d.anchor.start},#{d.anchor.end},#{d.anchor.quote},#{d.question},#{d.answer},#{d.detail},#{color},#{now})
        """)
    void saveHighlight(@Param("user") long user, @Param("d") Draft draft, @Param("color") String color,
                       @Param("now") LocalDateTime now);

    @Select("""
        SELECT b.block_no FROM reading_highlights h
        JOIN materials m ON m.id=h.material_id AND m.user_id=h.user_id
          AND m.parse_status='PARSED' AND m.parse_attempt=h.parse_attempt
        JOIN material_blocks b ON b.id=h.block_id AND b.material_id=h.material_id
        WHERE h.id=#{id} AND h.user_id=#{user} AND h.material_id=#{material}
        """)
    Integer studyBlock(@Param("user") long user, @Param("material") long material, @Param("id") String id);

    @Select("""
        SELECT t.id,t.chapter,t.group_name AS groupName,t.name,
               MAX(CASE WHEN a.block_id=h.block_id AND
                   (LOCATE(CAST(a.quote AS BINARY),CAST(h.quote AS BINARY))>0 OR
                    (CHAR_LENGTH(h.quote)>=4 AND LOCATE(CAST(h.quote AS BINARY),CAST(a.quote AS BINARY))>0))
                   THEN 1 ELSE 0 END) AS suggested
        FROM reading_highlights h
        JOIN adaptive_topic_sources a ON a.user_id=h.user_id AND a.material_id=h.material_id AND a.parse_attempt=h.parse_attempt
        JOIN adaptive_topics t ON t.id=a.topic_id AND t.user_id=h.user_id AND t.material_id=h.material_id
        WHERE h.id=#{id} AND h.user_id=#{user} AND h.material_id=#{material}
        GROUP BY t.id,t.chapter,t.group_name,t.name
        ORDER BY suggested DESC,t.chapter,t.group_name,t.name,t.id LIMIT 200
        """)
    List<StudyTopic> studyTopics(@Param("user") long user, @Param("material") long material, @Param("id") String id);

    @Insert("""
        INSERT INTO reading_highlight_study(highlight_id,intent,topic_id)
        VALUES(#{id},#{edit.intent},#{edit.topicId}) AS incoming
        ON DUPLICATE KEY UPDATE intent=incoming.intent,topic_id=incoming.topic_id
        """)
    void saveStudy(@Param("id") String id, @Param("edit") StudyEdit edit);

    @Delete("DELETE FROM reading_highlights WHERE user_id=#{user} AND material_id=#{material} AND id=#{id}")
    int deleteHighlight(@Param("user") long user, @Param("material") long material, @Param("id") String id);
}
