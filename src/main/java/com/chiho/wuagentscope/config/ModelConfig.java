package com.chiho.wuagentscope.config;

import lombok.Data;

import java.util.List;

/**
 * 模型配置属性
 * <p>
 * 对应 application.yml 中 agentscope.models.available 列表的每一项。
 * @author ChiHo
 */
@Data
public class ModelConfig {

    /** 模型唯一标识（如 "qwen3:14b"、"deepseek-r1:14b"） */
    private String id;

    /** 模型提供者（ollama / dashscope / openai） */
    private String provider;

    /** 模型服务地址（Ollama 场景必填） */
    private String baseUrl;

    /** 模型名称（传给提供者的实际模型标识） */
    private String modelName;

    /** 前端展示名称（如 "通义千问 14B"） */
    private String displayName;

    /** API Key（云端模型场景必填） */
    private String apiKey;

    /** ReAct 循环最大迭代次数，默认 20 */
    private int maxIters = 20;

    /** 模型能力标签（如 text / vision / audio），前端据此决定是否显示图片上传 */
    private List<String> capabilities;

    /**
     * 思维链（thinking）模式配置。
     * <p>
     * 可选值：
     * - disabled: 关闭 thinking，降低延迟（适合常规聊天）
     * - enabled:  开启 thinking（默认值，适合复杂推理）
     * - low:      低强度思考
     * - medium:   中等思考
     * - high:     深度思考
     * <p>
     * 不设置时，由 Ollama 模型自身决定（Qwen3 系列默认开启 thinking）。
     */
    private String think;
}
