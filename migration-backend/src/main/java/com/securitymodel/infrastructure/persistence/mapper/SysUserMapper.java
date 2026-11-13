package com.securitymodel.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.securitymodel.domain.user.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
    @Select("""
            SELECT user_id, username, nick_name, password, enabled,
                   CAST(is_admin AS UNSIGNED) AS admin
            FROM sys_user WHERE username = #{username} LIMIT 1
            """)
    SysUser findByUsername(String username);

    /**
     * Account state for token revalidation. {@code is_admin} is {@code bit(1)} in the schema,
     * so it is cast to a number instead of relying on driver-specific boolean mapping.
     */
    @Select("""
            SELECT user_id, username, nick_name, enabled,
                   CAST(is_admin AS UNSIGNED) AS admin
            FROM sys_user WHERE user_id = #{userId} LIMIT 1
            """)
    SysUser findStateById(Long userId);
}
