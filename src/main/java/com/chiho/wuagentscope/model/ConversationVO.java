package com.chiho.wuagentscope.model;

import lombok.Data;

/**
 * 会话信息VO（返回给前端）
 */
@Data
public class ConversationVO {

    /** 会话ID */
    private String conversationId;

    /** 会话名称 */
    private String conversationName;

    /** 创建时间 */
    private String createTime;
}
