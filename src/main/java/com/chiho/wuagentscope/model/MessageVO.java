package com.chiho.wuagentscope.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对话历史消息VO（返回给前端）
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MessageVO {

    /** 消息角色：user / assistant / system / tool */
    private String role;

    /** 消息文本内容 */
    private String content;

    /** 消息关联的图片列表（URL 或 data:image/xxx;base64,... 格式） */
    private List<String> imageUrls;

    /** 图表数据（ECharts option JSON，由 ChartSuggestTool 生成） */
    private String chartData;

    /** 消息时间戳 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime timestamp;
}
