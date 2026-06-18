-- Agent 调用日志表
-- 用于记录 LLM 调用、工具调用的关键指标，支持：
-- - Token 用量统计与成本分析
-- - 工具调用成功率监控
-- - 响应耗时性能分析
-- - 异常检测与告警

CREATE TABLE IF NOT EXISTS `agent_call_log` (
    `id`              BIGINT       AUTO_INCREMENT PRIMARY KEY,
    `conversation_id` VARCHAR(64)  NOT NULL     COMMENT '会话ID（chatId）',
    `user_id`         BIGINT       NOT NULL     COMMENT '用户ID',
    `run_id`          VARCHAR(64)  NOT NULL     COMMENT 'AgentScope run_id',
    `event_type`      VARCHAR(32)  NOT NULL     COMMENT '事件类型：MODEL_CALL_END / TOOL_RESULT_END / AGENT_END',
    `input_tokens`    INT                      COMMENT '输入 token 数',
    `output_tokens`   INT                      COMMENT '输出 token 数',
    `model_name`      VARCHAR(64)              COMMENT '模型名称',
    `tool_name`       VARCHAR(64)              COMMENT '工具名称',
    `tool_state`      VARCHAR(16)              COMMENT '工具调用结果状态：SUCCESS / ERROR / DENIED / INTERRUPTED',
    `duration_ms`     BIGINT                   COMMENT '耗时（毫秒）',
    `detail`          JSON                     COMMENT '完整事件详情 JSON',
    `created_at`      DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX `idx_conversation` (`conversation_id`),
    INDEX `idx_user_time` (`user_id`, `created_at`),
    INDEX `idx_run_id` (`run_id`),
    INDEX `idx_event_type` (`event_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 调用日志';
