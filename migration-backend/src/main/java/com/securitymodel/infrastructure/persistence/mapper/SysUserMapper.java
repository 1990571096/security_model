package com.securitymodel.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.securitymodel.domain.user.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
    @Select("""
            SELECT user_id, username, nick_name, password, enabled,
                   CAST(is_admin AS UNSIGNED) AS admin
            FROM sys_user WHERE username = #{username} AND deleted_at IS NULL LIMIT 1
            """)
    SysUser findByUsername(String username);

    /**
     * Account state for token revalidation. {@code is_admin} is {@code bit(1)} in the schema,
     * so it is cast to a number instead of relying on driver-specific boolean mapping.
     */
    @Select("""
            SELECT user_id, username, nick_name, enabled,
                   CAST(is_admin AS UNSIGNED) AS admin
            FROM sys_user WHERE user_id = #{userId} AND deleted_at IS NULL LIMIT 1
            """)
    SysUser findStateById(Long userId);

    @Select("""
            SELECT user_id, username, nick_name, enabled, CAST(is_admin AS UNSIGNED) AS admin
            FROM sys_user WHERE deleted_at IS NULL ORDER BY user_id
            """)
    List<SysUser> listAccountStates();

    @Update("""
            UPDATE sys_user SET enabled = #{enabled}, update_by = #{operator}, update_time = CURRENT_TIMESTAMP
            WHERE user_id = #{userId} AND COALESCE(is_admin, b'0') = b'0' AND deleted_at IS NULL
            """)
    int setExperimenterEnabled(Long userId, boolean enabled, String operator);

    @Update("""
            UPDATE sys_user
            SET deleted_at = CURRENT_TIMESTAMP(3), enabled = b'0', password = NULL,
                update_by = #{operator}, update_time = CURRENT_TIMESTAMP
            WHERE user_id = #{userId} AND COALESCE(is_admin, b'0') = b'0' AND deleted_at IS NULL
            """)
    int deleteExperimenter(Long userId, String operator);

    @Select("SELECT EXISTS(SELECT 1 FROM sys_user WHERE username = #{username} AND deleted_at IS NOT NULL)")
    boolean isDeletedUsername(String username);
}
