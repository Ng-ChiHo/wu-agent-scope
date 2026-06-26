package com.chiho.wuagentscope.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
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
 * 工具结果截断中间件
 * <p>
 * 在 onReasoning 阶段扫描消息列表，将超长的工具返回结果截断，
 * 保留前 N 个字符 + 截断提示，避免工具返回撑爆上下文。
 * <p>
 * 典型场景：web_read 返回整个网页内容（5000+ 字）、SQL 查询返回大量数据。
 *
 * @author ChiHo
 */
@Component
@Slf4j
public class ToolResultTrimMiddleware implements MiddlewareBase {

    @Value("${agent.tool-result.max-chars:2000}")
    private int maxChars;

    @Value("${agent.tool-result.preview-chars:300}")
    private int previewChars;

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx,
                                     AgentInput input, Function<AgentInput, Flux<AgentEvent>> next) {
        return next.apply(input);
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx,
                                         ReasoningInput input, Function<ReasoningInput, Flux<AgentEvent>> next) {
        List<Msg> messages = input.messages();
        if (messages == null || messages.isEmpty()) {
            return next.apply(input);
        }

        List<Msg> trimmed = trimToolResults(messages);

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

    /**
     * 扫描消息列表，截断超长的工具返回结果
     */
    private List<Msg> trimToolResults(List<Msg> messages) {
        List<Msg> result = null;
        int trimmedCount = 0;

        for (int i = 0; i < messages.size(); i++) {
            Msg msg = messages.get(i);
            Msg trimmed = trimSingleMessage(msg);
            if (trimmed != msg) {
                if (result == null) {
                    result = new ArrayList<>(messages);
                }
                result.set(i, trimmed);
                trimmedCount++;
            }
        }

        if (trimmedCount > 0) {
            log.info("工具结果截断: {} 条消息中的工具返回被截断 (maxChars={})", trimmedCount, maxChars);
            return result;
        }
        return messages;
    }

    /**
     * 截断单条消息中的超长工具结果
     */
    private Msg trimSingleMessage(Msg msg) {
        List<ContentBlock> content = msg.getContent();
        if (content == null || content.isEmpty()) {
            return msg;
        }

        boolean changed = false;
        List<ContentBlock> newContent = null;

        for (int i = 0; i < content.size(); i++) {
            ContentBlock block = content.get(i);
            if (!(block instanceof ToolResultBlock toolResult)) {
                continue;
            }

            // 提取工具结果文本
            List<ContentBlock> output = toolResult.getOutput();
            if (output == null || output.isEmpty()) {
                continue;
            }

            StringBuilder textBuilder = new StringBuilder();
            for (ContentBlock outBlock : output) {
                if (outBlock instanceof TextBlock textBlock) {
                    textBuilder.append(textBlock.getText());
                }
            }
            String text = textBuilder.toString();
            if (text.length() <= maxChars) {
                continue;
            }

            // 截断：保留前 previewChars + 中间省略提示 + 后 previewChars
            if (newContent == null) {
                newContent = new ArrayList<>(content);
            }

            String truncated = text.substring(0, previewChars)
                    + "\n\n... [内容过长，已截断 " + text.length() + " -> " + previewChars + " 字符] ..."
                    + "\n\n" + text.substring(text.length() - previewChars);

            ToolResultBlock trimmedResult = ToolResultBlock.builder()
                    .id(toolResult.getId())
                    .name(toolResult.getName())
                    .output(TextBlock.builder().text(truncated).build())
                    .state(toolResult.getState())
                    .build();

            newContent.set(i, trimmedResult);
            changed = true;
        }

        if (!changed) {
            return msg;
        }

        return msg.withContent(newContent);
    }
}
