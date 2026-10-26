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
}
