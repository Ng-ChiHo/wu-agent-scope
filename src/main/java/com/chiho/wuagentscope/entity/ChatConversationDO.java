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

    /** 最近使用的模型ID */
    @TableField("last_model_id")
    private String lastModelId;

    /** 最近一次路由类型（如 general、car_advisor、data_analyst） */
    @TableField("last_route")
    private String lastRoute;

    /** 最近一次路由时的用户消息（用于多轮对话路由上下文） */
    @TableField("last_route_msg")
    private String lastRouteMsg;

    /** 创建时间 */
    @TableField("create_time")
    private LocalDateTime createTime;

    /** 修改时间 */
    @TableField("update_time")
    private LocalDateTime updateTime;
}
