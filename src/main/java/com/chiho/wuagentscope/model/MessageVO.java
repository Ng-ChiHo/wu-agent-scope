package com.chiho.wuagentscope.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对话历史消息VO（返回给前端）
 */
@Data
public class MessageVO {

    /** 消息角色：user / assistant / system / tool */
    private String role;

    /** 消息文本内容 */
    private String content;

    /** 消息时间戳 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime timestamp;
}
