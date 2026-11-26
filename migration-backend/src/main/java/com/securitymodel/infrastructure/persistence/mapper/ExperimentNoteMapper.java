package com.securitymodel.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.securitymodel.domain.note.ExperimentNote;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface ExperimentNoteMapper extends BaseMapper<ExperimentNote> {

    @Select("""
            SELECT * FROM sys_experiment_note
            WHERE note_id = #{noteId} AND user_id = #{userId} AND status = 'active'
            """)
    ExperimentNote findOwned(@Param("noteId") Long noteId, @Param("userId") Long userId);

    @Select("""
            SELECT * FROM sys_experiment_note
            WHERE user_id = #{userId} AND status = 'active'
            ORDER BY update_time DESC
            LIMIT #{limit}
            """)
    List<ExperimentNote> findByUser(@Param("userId") Long userId, @Param("limit") int limit);

    @Select("""
            SELECT * FROM sys_experiment_note
            WHERE user_id = #{userId} AND request_key = #{requestKey}
            LIMIT 1
            """)
    ExperimentNote findByRequestKey(@Param("userId") Long userId,
                                    @Param("requestKey") String requestKey);

    @Update("""
            UPDATE sys_experiment_note
            SET status = 'deleted', update_time = #{now}
            WHERE note_id = #{noteId} AND user_id = #{userId} AND status = 'active'
            """)
    int softDelete(@Param("noteId") Long noteId, @Param("userId") Long userId,
                   @Param("now") java.time.LocalDateTime now);
}
