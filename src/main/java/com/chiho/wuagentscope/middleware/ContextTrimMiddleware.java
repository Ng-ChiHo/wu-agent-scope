package com.chiho.wuagentscope.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ReasoningInput;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 上下文截断中间件
 * <p>
 * 在 ReAct 推理阶段（onReasoning）截断过长的对话历史，只保留最近 N 轮。
 * 此时消息列表已从 MySQL 加载完毕，包含完整历史。
 * <p>
 * 截断只影响发给 LLM 的输入，MySQL 中的 AgentState 仍保留完整历史。
 * <p>
 * 注意：不能在 onAgent 阶段截断，因为 input.msgs() 只包含当前新消息，
 * 历史对话在中间件之后才从 AgentStateStore 加载。
 *
 * @author ChiHo
 */
@Component
@Slf4j
public class ContextTrimMiddleware implements MiddlewareBase {

    @Value("${agent.context.max-rounds:20}")
    private int maxRounds;

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx,
                                     AgentInput input, Function<AgentInput, Flux<AgentEvent>> next) {
        // onAgent 阶段 input.msgs() 只有新消息，不做截断
        return next.apply(input);
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx,
                                         ReasoningInput input, Function<ReasoningInput, Flux<AgentEvent>> next) {
        List<Msg> original = input.messages();

        if (original == null || original.size() <= 2) {
            return next.apply(input);
        }

        List<Msg> trimmed = trimContext(original, maxRounds);

        if (trimmed.size() < original.size()) {
            log.info("推理上下文截断: {} -> {} 条消息 (maxRounds={})",
                    original.size(), trimmed.size(), maxRounds);
        }

        return next.apply(new ReasoningInput(trimmed, input.tools(), input.options()));
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx,
                                      ActingInput input, Function<ActingInput, Flux<AgentEvent>> next) {
        return next.apply(input);
    }

    @Override
    public Flux<AgentEvent> onModelCall(Agent agent, RuntimeContext ctx,
                                         ModelCallInput input, Function<ModelCallInput, Flux<AgentEvent>> next) {
        return next.apply(input);
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String systemPrompt) {
        return MiddlewareBase.super.onSystemPrompt(agent, ctx, systemPrompt);
    }

    /**
     * 截断上下文消息列表
     * <p>
     * 策略：
     * 1. 保留第一条消息（system prompt）
     * 2. 保留最近 maxRounds 轮对话
     * 3. 超出部分丢弃（MySQL 中仍有完整历史）
     */
    private List<Msg> trimContext(List<Msg> messages, int maxRounds) {
        // 最多保留 2 * maxRounds 条消息（每轮 = 用户 + 助手）+ 1 条首消息
        int maxMessages = maxRounds * 2 + 1;

        if (messages.size() <= maxMessages) {
            return messages;
        }

        List<Msg> result = new ArrayList<>(maxMessages + 1);

        // 保留第一条消息（system prompt）
        result.add(messages.get(0));

        // 保留最后 maxMessages - 1 条消息
        int fromIndex = messages.size() - (maxMessages - 1);
        result.addAll(messages.subList(fromIndex, messages.size()));

        return result;
    }
}
