package com.chiho.wuagentscope.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI会话关联实体
 * 对应表：ai_chat_conversation
 */
@Data
@TableName("ai_chat_conversation")
public class ChatConversationDO {

    /** 主键ID */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户ID */
    @TableField("user_id")
    private Long userId;

    /** 会话ID */
    @TableField("conversation_id")
    private String conversationId;

    /** 会话名称 */
    @TableField("conversation_name")
    private String conversationName;

    /** 创建时间 */
    @TableField("create_time")
    private LocalDateTime createTime;

    /** 修改时间 */
    @TableField("update_time")
    private LocalDateTime updateTime;
}
