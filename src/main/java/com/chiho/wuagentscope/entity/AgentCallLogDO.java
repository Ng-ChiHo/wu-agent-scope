package com.chiho.wuagentscope.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 调用日志实体
 * <p>
 * 记录每次 LLM 调用、工具调用的关键指标，用于：
 * - Token 用量统计与成本分析
 * - 工具调用成功率监控
 * - 响应耗时性能分析
 * - 异常检测与告警
 *
 * @author ChiHo
 */
@Data
@TableName("agent_call_log")
public class AgentCallLogDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会话ID（chatId） */
    @TableField("conversation_id")
    private String conversationId;

    /** 用户ID */
    @TableField("user_id")
    private String userId;

    /** AgentScope run_id（一次完整 call 的唯一标识） */
    @TableField("run_id")
    private String runId;

    /** 事件类型：MODEL_CALL_END / TOOL_RESULT_END / AGENT_END */
    @TableField("event_type")
    private String eventType;

    /** 输入 token 数 */
    @TableField("input_tokens")
    private Integer inputTokens;

    /** 输出 token 数 */
    @TableField("output_tokens")
    private Integer outputTokens;

    /** 模型名称 */
    @TableField("model_name")
    private String modelName;

    /** 工具名称（工具调用事件时有值） */
    @TableField("tool_name")
    private String toolName;

    /** 工具调用结果状态：SUCCESS / ERROR / DENIED / INTERRUPTED */
    @TableField("tool_state")
    private String toolState;

    /** 耗时（毫秒） */
    @TableField("duration_ms")
    private Long durationMs;

    /** 完整事件详情 JSON（可选，用于调试） */
    @TableField("detail")
    private String detail;

    /** 创建时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
