package com.learnloop.journal;

import static com.learnloop.journal.PdfAnnotationApi.*;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
public interface PdfAnnotationMapper {
    String SELECT = """
        SELECT p.id,p.page_number AS pageNumber,p.quote,p.rects,p.color,p.revision,
            p.create_hash AS createHash,p.deleted,p.note_id AS noteId,n.content AS note,n.revision AS noteRevision
        FROM pdf_annotations p
        LEFT JOIN reading_notes n ON n.id=p.note_id AND n.user_id=p.user_id
        WHERE p.user_id=#{user} AND p.material_id=#{material}
        """;

    @Select("""
        SELECT EXISTS(SELECT 1 FROM information_schema.tables
          WHERE table_schema=DATABASE() AND table_name='pdf_annotations' AND table_type='BASE TABLE')
        """)
    boolean ready();

    @Select(SELECT + " AND p.deleted=0 ORDER BY p.page_number,p.created_at,p.id LIMIT 2000")
    List<Row> list(@Param("user") long user, @Param("material") long material);

    @Select(SELECT + " AND p.id=#{id}")
    Row find(@Param("user") long user, @Param("material") long material, @Param("id") String id);

    @Select("SELECT COUNT(*) FROM pdf_annotations WHERE user_id=#{user} AND material_id=#{material} AND deleted=0")
    int count(@Param("user") long user, @Param("material") long material);

    @Insert("""
        INSERT INTO pdf_annotations(id,user_id,material_id,page_number,quote,rects,color,revision,
            create_hash,note_id,created_at,updated_at)
        VALUES(#{id},#{user},#{material},#{page},#{quote},#{rects},#{color},#{revision},#{hash},#{noteId},#{now},#{now})
        """)
    void insert(@Param("user") long user, @Param("material") long material, @Param("id") String id,
                @Param("page") int page, @Param("quote") String quote, @Param("rects") String rects,
                @Param("color") String color, @Param("revision") String revision, @Param("hash") String hash,
                @Param("noteId") String noteId, @Param("now") LocalDateTime now);

    @Update("""
        UPDATE pdf_annotations SET color=#{color},revision=#{revision},updated_at=#{now}
        WHERE user_id=#{user} AND material_id=#{material} AND id=#{id} AND deleted=0
        """)
    void update(@Param("user") long user, @Param("material") long material, @Param("id") String id,
                @Param("color") String color, @Param("revision") String revision, @Param("now") LocalDateTime now);

    @Update("""
        UPDATE pdf_annotations SET deleted=1,revision=#{revision},updated_at=#{now}
        WHERE user_id=#{user} AND material_id=#{material} AND id=#{id}
        """)
    void delete(@Param("user") long user, @Param("material") long material, @Param("id") String id,
                @Param("revision") String revision, @Param("now") LocalDateTime now);

    @Insert("""
        INSERT INTO reading_notes(id,user_id,material_id,quote,question,content,revision,updated_at)
        VALUES(#{id},#{user},#{material},#{quote},'阅读笔记',#{content},#{revision},#{now})
        """)
    void insertNote(@Param("user") long user, @Param("material") long material, @Param("id") String id,
                    @Param("quote") String quote, @Param("content") String content,
                    @Param("revision") String revision, @Param("now") LocalDateTime now);

    @Delete("DELETE FROM reading_notes WHERE id=#{id} AND user_id=#{user}")
    void deleteNote(@Param("user") long user, @Param("id") String id);
}
