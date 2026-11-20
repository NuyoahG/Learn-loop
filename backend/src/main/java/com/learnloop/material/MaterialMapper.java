package com.learnloop.material;

import org.apache.ibatis.annotations.*;
import java.util.List;

/** 所有查询的第一个参数都为服务端确认的用户 ID；唯一约束解决并发，查重仅作快路径。 */
@Mapper
public interface MaterialMapper {
    String COLUMNS = "id,user_id,original_name,file_type,size_bytes,sha256,storage_key,request_id,"
            + "parse_status,parse_attempt,parser_version,error_code,error_message,warnings,"
            + "block_count,text_char_count,created_at,updated_at,parsed_at";

    @Select("SELECT " + COLUMNS + " FROM materials WHERE user_id=#{userId} AND request_id=#{requestId}")
    MaterialRow findByRequestId(@Param("userId") Long userId, @Param("requestId") String requestId);

    @Select("SELECT " + COLUMNS + " FROM materials WHERE user_id=#{userId} AND id=#{id}")
    MaterialRow findById(@Param("userId") Long userId, @Param("id") Long id);

    @Select("SELECT COUNT(*) FROM materials WHERE user_id=#{userId}")
    long count(@Param("userId") Long userId);

    @Select("SELECT " + COLUMNS + " FROM materials WHERE user_id=#{userId}"
            + " ORDER BY created_at DESC,id DESC LIMIT #{size} OFFSET #{offset}")
    List<MaterialRow> findPage(@Param("userId") Long userId, @Param("size") int size,
                               @Param("offset") long offset);

    @Insert("""
        INSERT INTO materials(user_id,original_name,file_type,size_bytes,sha256,storage_key,request_id,
            parse_status,parse_attempt,warnings,block_count,text_char_count,created_at,updated_at)
        VALUES(#{userId},#{originalName},#{fileType},#{sizeBytes},#{sha256},#{storageKey},#{requestId},
            'UPLOADED',0,JSON_ARRAY(),0,0,#{createdAt},#{updatedAt})
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(MaterialRow row);
}
