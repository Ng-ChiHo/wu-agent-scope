package com.chiho.wuagentscope.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
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
import java.util.stream.Collectors;

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

    /** 保留最近 N 轮的图片，更早的消息只保留文字（避免图片撑爆上下文） */
    @Value("${agent.context.image-keep-rounds:2}")
    private int imageKeepRounds;

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
     * 截断上下文消息列表 + 剥离历史图片
     * <p>
     * 策略：
     * 1. 保留第一条消息（system prompt）
     * 2. 保留最近 maxRounds 轮对话
     * 3. 超出部分丢弃（MySQL 中仍有完整历史）
     * 4. 对保留的消息，剥离最近 imageKeepRounds 轮之前的图片
     */
    private List<Msg> trimContext(List<Msg> messages, int maxRounds) {
        // 最多保留 2 * maxRounds 条消息（每轮 = 用户 + 助手）+ 1 条首消息
        int maxMessages = maxRounds * 2 + 1;

        List<Msg> trimmed;
        if (messages.size() <= maxMessages) {
            trimmed = new ArrayList<>(messages);
        } else {
            trimmed = new ArrayList<>(maxMessages + 1);
            // 保留第一条消息（system prompt）
            trimmed.add(messages.get(0));
            // 保留最后 maxMessages - 1 条消息
            int fromIndex = messages.size() - (maxMessages - 1);
            trimmed.addAll(messages.subList(fromIndex, messages.size()));
        }

        // 剥离历史图片：只保留最近 imageKeepRounds 轮的图片
        return stripOldImages(trimmed, imageKeepRounds);
    }

    /**
     * 剥离历史轮次中的图片，只保留最近 N 轮的图片给到 LLM，用户在查询的时候依然保持所有的历史记录
     * <p>
     * 图片 base64 数据量大（单张 1-5MB），历史图片对当前对话价值低，
     * 剥离后可大幅减少传输量和模型处理时间。
     * <p>
     * 策略：
     * - 从消息列表末尾向前扫描
     * - 最近 imageKeepRounds 轮（每轮 = 用户 + 助手）的图片保留
     * - 更早的消息如果有图片，只保留文字部分，附加 "[图片已省略]" 提示
     */
    private List<Msg> stripOldImages(List<Msg> messages, int imageKeepRounds) {
        // 保留最近 imageKeepRounds * 2 条消息的图片（每轮 = 用户 + 助手）
        int imageKeepCount = imageKeepRounds * 2;
        // 首条消息（system prompt）不算在内
        int messageCount = messages.size() - 1;

        if (messageCount <= imageKeepCount) {
            return messages; // 消息数不够，不需要剥离
        }

        List<Msg> result = new ArrayList<>(messages.size());
        result.add(messages.get(0)); // 首条消息原样保留

        int strippedCount = 0;
        for (int i = 1; i < messages.size(); i++) {
            Msg msg = messages.get(i);
            // 计算这条消息距离末尾的位置
            int fromEnd = messages.size() - 1 - i;

            if (fromEnd < imageKeepCount) {
                // 在保留范围内，原样保留
                result.add(msg);
            } else {
                // 超出保留范围，剥离图片
                Msg stripped = stripImages(msg);
                if (stripped != msg) {
                    strippedCount++;
                }
                result.add(stripped);
            }
        }

        if (strippedCount > 0) {
            log.info("剥离历史图片: {} 条消息中的图片已移除 (保留最近 {} 轮图片)",
                    strippedCount, imageKeepRounds);
        }

        return result;
    }

    /**
     * 从单条消息中移除 ImageBlock，只保留 TextBlock
     * <p>
     * 如果消息没有图片，直接返回原消息。
     * 如果有图片，创建新消息，文字内容末尾附加 "[图片已省略]" 提示。
     */
    private Msg stripImages(Msg msg) {
        if (!msg.hasContentBlocks(ImageBlock.class)) {
            return msg; // 没有图片，原样返回
        }

        List<ContentBlock> textBlocks = msg.getContent().stream()
                .filter(block -> block instanceof TextBlock)
                .collect(Collectors.toList());

        if (textBlocks.isEmpty()) {
            // 纯图片消息，替换为提示文字
            textBlocks = List.of(TextBlock.builder().text("[图片已省略]").build());
        } else {
            // 在文字末尾附加提示
            List<ContentBlock> newTextBlocks = new ArrayList<>(textBlocks);
            TextBlock lastText = (TextBlock) textBlocks.get(textBlocks.size() - 1);
            newTextBlocks.set(newTextBlocks.size() - 1,
                    TextBlock.builder().text(lastText.getText() + "\n[图片已省略]").build());
            textBlocks = newTextBlocks;
        }

        return msg.withContent(textBlocks);
    }
}
