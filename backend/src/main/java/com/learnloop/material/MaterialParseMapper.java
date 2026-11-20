package com.learnloop.material;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

/**
 * 锁与更新均发生在 Service 的短事务内。业务查询必须带归属；仅恢复扫描跨用户。
 * parse_attempt 是栅栏：旧 HTTP 返回、旧失败回调均不能修改新尝试。
 */
@Mapper
public interface MaterialParseMapper {
    @Insert("""
        INSERT INTO material_model_noise(material_id,parse_attempt,block_no,ranges_json)
        VALUES(#{material},#{attempt},#{block},CAST(#{ranges} AS JSON))
        """)
    void insertModelNoise(@Param("material") long material,@Param("attempt") long attempt,
                          @Param("block") int block,@Param("ranges") String ranges);
    @Select("SELECT id FROM users WHERE id=#{userId} FOR UPDATE")
    Long lockUser(Long userId);

    @Select("SELECT " + MaterialMapper.COLUMNS + " FROM materials WHERE user_id=#{userId} AND id=#{id} FOR UPDATE")
    MaterialRow lockOwned(@Param("userId") Long userId, @Param("id") Long id);

    @Select("SELECT COUNT(*) FROM materials WHERE user_id=#{userId} AND parse_status='PARSING'")
    int activeForUser(Long userId);

    @Update("""
        UPDATE materials SET parse_status='PARSING',parse_attempt=parse_attempt+1,
        error_code=NULL,error_message=NULL,updated_at=#{now}
        WHERE user_id=#{userId} AND id=#{id} AND parse_attempt=#{parseAttempt}
        AND parse_status IN ('UPLOADED','FAILED')
        """)
    int claim(@Param("userId") Long userId, @Param("id") Long id,
              @Param("parseAttempt") long attempt, @Param("now") LocalDateTime now);

    @Update("""
        UPDATE materials SET parse_status=#{previous.parseStatus},error_code=#{previous.errorCode},
        error_message=#{previous.errorMessage},updated_at=#{now}
        WHERE user_id=#{previous.userId} AND id=#{previous.id}
        AND parse_status='PARSING' AND parse_attempt=#{attempt}
        """)
    int restoreRejected(@Param("previous") MaterialRow previous, @Param("attempt") long attempt,
                        @Param("now") LocalDateTime now);

    @Update("""
        UPDATE materials SET parse_status='FAILED',error_code=#{code},error_message=#{message},updated_at=#{now}
        WHERE user_id=#{userId} AND id=#{id} AND parse_status='PARSING' AND parse_attempt=#{attempt}
        """)
    int fail(@Param("userId") Long userId,@Param("id") Long id,@Param("attempt") long attempt,
             @Param("code") String code,@Param("message") String message,@Param("now") LocalDateTime now);

    @Delete("""
        DELETE b FROM material_blocks b JOIN materials m ON m.id=b.material_id
        WHERE m.user_id=#{userId} AND m.id=#{id}
        """)
    int clearBlocks(@Param("userId") Long userId, @Param("id") Long id);

    @Insert("""
        INSERT INTO material_blocks(material_id,block_no,block_type,text_content,source_locator)
        VALUES(#{materialId},#{blockNo},#{blockType},#{textContent},#{sourceLocator})
        """)
    int insertBlock(MaterialBlockRow block);

    @Update("""
        UPDATE materials SET parse_status='PARSED',parser_version=#{version},warnings=#{warnings},
        block_count=#{count},text_char_count=#{characters},error_code=NULL,error_message=NULL,
        parsed_at=#{now},updated_at=#{now}
        WHERE user_id=#{userId} AND id=#{id} AND parse_status='PARSING' AND parse_attempt=#{attempt}
        """)
    int publish(@Param("userId") Long userId,@Param("id") Long id,@Param("attempt") long attempt,
                @Param("version") String version,@Param("warnings") String warnings,@Param("count") int count,
                @Param("characters") int characters,@Param("now") LocalDateTime now);

    @Select("""
        SELECT b.* FROM material_blocks b JOIN materials m ON m.id=b.material_id
        WHERE m.user_id=#{userId} AND m.id=#{id} AND m.parse_status='PARSED'
        ORDER BY b.block_no LIMIT #{size} OFFSET #{offset}
        """)
    List<MaterialBlockRow> blocks(@Param("userId") Long userId,@Param("id") Long id,
                                  @Param("size") int size,@Param("offset") long offset);

    @Select("SELECT " + MaterialMapper.COLUMNS + " FROM materials WHERE parse_status='PARSING' AND updated_at < #{cutoff} LIMIT 100")
    List<MaterialRow> stale(LocalDateTime cutoff);

    @Update("""
        UPDATE materials SET parse_status='FAILED',error_code='PARSER_INTERRUPTED',
        error_message='上次处理被中断，请手动重新解析。',updated_at=#{now}
        WHERE parse_status='PARSING'
        """)
    int interruptOnStartup(LocalDateTime now);
}
