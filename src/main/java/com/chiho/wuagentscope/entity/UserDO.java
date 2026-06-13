package com.chiho.wuagentscope.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统用户实体
 * 对应表：ai_user
 */
@Data
@TableName("ai_user")
public class UserDO {

    /** 用户ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户登录名 */
    @TableField("username")
    private String username;

    /** 密码（BCrypt加密） */
    @TableField("password")
    private String password;

    /** 昵称 */
    @TableField("nickname")
    private String nickname;

    /** 邮箱 */
    @TableField("email")
    private String email;

    /** 电话 */
    @TableField("phone")
    private String phone;

    /** 状态：1-正常，0-禁用 */
    @TableField("status")
    private Integer status;

    /** 创建时间 */
    @TableField("create_time")
    private LocalDateTime createTime;

    /** 修改时间 */
    @TableField("update_time")
    private LocalDateTime updateTime;
}
