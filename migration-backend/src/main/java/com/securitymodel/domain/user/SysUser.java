package com.securitymodel.domain.user;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("sys_user")
public class SysUser {
    @TableId("user_id")
    private Long userId;
    private String username;
    @TableField("nick_name")
    private String nickName;
    private String password;
    private Boolean enabled;
    @TableField("is_admin")
    private Integer admin;
}
