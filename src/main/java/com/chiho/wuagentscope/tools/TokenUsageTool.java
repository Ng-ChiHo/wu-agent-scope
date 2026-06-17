package com.chiho.wuagentscope.tools;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.chiho.wuagentscope.entity.AgentCallLogDO;
import com.chiho.wuagentscope.mapper.AgentCallLogMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Token 用量查询工具
 * <p>
 * 查询当前登录用户的 token 使用总量。
 * 通过 RuntimeContext 自动注入获取 userId（AgentScope 框架能力，无需 @ToolParam）。
 *
 * @author Chiho
 */
@Component
@Slf4j
public class TokenUsageTool {

    @Resource
    private AgentCallLogMapper agentCallLogMapper;

    @Tool(name = "query_token_usage", description = "查询当前用户的 token 使用总量。当用户询问自己消耗了多少 token、调用了多少次、使用统计时调用。")
    public String queryTokenUsage(RuntimeContext ctx) {
        String userId = ctx.getUserId();
        if (userId == null) {
            return "Error: unable to get current user info";
        }

        try {
            // 查询 AGENT_END 记录的汇总数据（每次 run 的 token 总量）
            Map<String, Object> result = agentCallLogMapper.selectMaps(
                    new QueryWrapper<AgentCallLogDO>()
                            .eq("user_id", userId)
                            .eq("event_type", "AGENT_END")
                            .select("SUM(input_tokens) as totalInput",
                                    "SUM(output_tokens) as totalOutput",
                                    "COUNT(*) as totalRuns")
            ).stream().findFirst().orElse(null);

            if (result == null || result.get("totalRuns") == null) {
                return "当前暂无 token 使用记录";
            }

            long totalInput = toLong(result.get("totalInput"));
            long totalOutput = toLong(result.get("totalOutput"));
            long totalRuns = toLong(result.get("totalRuns"));

            return String.join("\n",
                    "用户 " + userId + " 的 Token 使用统计：",
                    "总调用次数: " + totalRuns + " 次",
                    "输入 Token: " + totalInput,
                    "输出 Token: " + totalOutput,
                    "合计 Token: " + (totalInput + totalOutput)
            );
        } catch (Exception e) {
            log.error("查询 token 用量失败: userId={}", userId, e);
            return "Error: query failed - " + e.getMessage();
        }
    }

    private long toLong(Object value) {
        if (value == null) return 0;
        if (value instanceof Number) return ((Number) value).longValue();
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
