package com.chiho.wuagentscope.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chiho.wuagentscope.common.exception.BusinessException;
import com.chiho.wuagentscope.common.exception.ErrorCode;
import com.chiho.wuagentscope.entity.AgentCallLogDO;
import com.chiho.wuagentscope.entity.ChatConversationDO;
import com.chiho.wuagentscope.mapper.AgentCallLogMapper;
import com.chiho.wuagentscope.mapper.ChatConversationMapper;
import com.chiho.wuagentscope.model.ConversationVO;
import com.chiho.wuagentscope.model.MessageVO;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 会话管理服务
 * <p>
 * 管理用户与会话的关联关系，数据持久化到 MySQL（ai_chat_conversation 表）。
 * 同时提供从 AgentStateStore 读取对话历史消息的能力。
 * @author ChiHo
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatConversationService {

    private final ChatConversationMapper chatConversationMapper;
    private final AgentCallLogMapper agentCallLogMapper;
    private final AgentStateStore agentStateStore;

    /** 时间戳格式：2026-06-13 20:04:02.071 */
    private static final DateTimeFormatter TS_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /**
     * 创建或获取会话
     * <p>
     * 首次使用某个 conversationId 时自动创建会话记录，
     * 后续调用直接返回已有记录。每次调用都会更新 lastModelId。
     *
     * @param userId         用户ID
     * @param conversationId 会话ID
     * @param firstMessage   首条消息（用于生成会话名称）
     * @param modelId        本次使用的模型ID（可选）
     * @return 会话记录
     */
    @Transactional(rollbackFor = Exception.class)
    public ChatConversationDO getOrCreateConversation(Long userId, String conversationId,
                                                      String firstMessage, String modelId) {
        // 查询是否已存在
        LambdaQueryWrapper<ChatConversationDO> queryWrapper = new LambdaQueryWrapper<ChatConversationDO>()
                .eq(ChatConversationDO::getUserId, userId)
                .eq(ChatConversationDO::getConversationId, conversationId);
        ChatConversationDO existing = chatConversationMapper.selectOne(queryWrapper);

        if (existing != null) {
            // 更新最近使用的模型
            if (modelId != null && !modelId.equals(existing.getLastModelId())) {
                existing.setLastModelId(modelId);
                existing.setUpdateTime(LocalDateTime.now());
                chatConversationMapper.updateById(existing);
            }
            return existing;
        }

        // 创建新会话
        String conversationName = generateConversationName(firstMessage);
        ChatConversationDO newConversation = new ChatConversationDO();
        newConversation.setUserId(userId);
        newConversation.setConversationId(conversationId);
        newConversation.setConversationName(conversationName);
        newConversation.setLastModelId(modelId);
        newConversation.setCreateTime(LocalDateTime.now());
        newConversation.setUpdateTime(LocalDateTime.now());
        chatConversationMapper.insert(newConversation);

        log.info("创建新会话: userId={}, conversationId={}, name={}", userId, conversationId, conversationName);
        return newConversation;
    }

    /**
     * 获取用户的会话列表（VO，按更新时间倒序）
     */
    public List<ConversationVO> listConversations(Long userId) {
        LambdaQueryWrapper<ChatConversationDO> queryWrapper = new LambdaQueryWrapper<ChatConversationDO>()
                .eq(ChatConversationDO::getUserId, userId)
                .orderByDesc(ChatConversationDO::getUpdateTime);
        return chatConversationMapper.selectList(queryWrapper).stream()
                .map(this::toConversationVO)
                .collect(Collectors.toList());
    }

    /**
     * 更新会话名称
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateConversationName(Long userId, String conversationId, String newName) {
        ChatConversationDO conversation = getAndCheckConversation(userId, conversationId);
        conversation.setConversationName(newName);
        conversation.setUpdateTime(LocalDateTime.now());
        chatConversationMapper.updateById(conversation);
        log.info("更新会话名称: userId={}, conversationId={}, newName={}", userId, conversationId, newName);
    }

    /**
     * 删除会话（同时删除 MySQL 会话记录 + AgentStateStore 对话状态 + Agent 调用日志）
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteConversation(Long userId, String conversationId) {
        getAndCheckConversation(userId, conversationId);

        // 删除 MySQL 会话记录
        LambdaQueryWrapper<ChatConversationDO> queryWrapper = new LambdaQueryWrapper<ChatConversationDO>()
                .eq(ChatConversationDO::getUserId, userId)
                .eq(ChatConversationDO::getConversationId, conversationId);
        chatConversationMapper.delete(queryWrapper);

        // 删除 Agent 调用日志
        LambdaQueryWrapper<AgentCallLogDO> logQueryWrapper = new LambdaQueryWrapper<AgentCallLogDO>()
                .eq(AgentCallLogDO::getUserId, userId)
                .eq(AgentCallLogDO::getConversationId, conversationId);
        agentCallLogMapper.delete(logQueryWrapper);

        // 删除 AgentStateStore 中的对话状态
        agentStateStore.delete(String.valueOf(userId), conversationId);

        log.info("删除会话: userId={}, conversationId={}", userId, conversationId);
    }

    /**
     * 查询对话历史消息列表
     * <p>
     * 从 AgentStateStore 中读取该会话的 AgentState，
     * 提取 context() 中的消息列表并转换为 MessageVO 返回。
     *
     * @param userId         用户ID
     * @param conversationId 会话ID
     * @return 消息列表（role + content + timestamp）
     */
    public List<MessageVO> getConversationMessages(Long userId, String conversationId) {
        // 验证会话是否存在
        getAndCheckConversation(userId, conversationId);

        // 从 AgentStateStore 读取对话历史
        try {
            var stateOpt = agentStateStore.get(
                    String.valueOf(userId),
                    conversationId,
                    "agent_state",
                    AgentState.class);

            if (stateOpt.isEmpty()) {
                return new ArrayList<>();
            }

            List<Msg> context = stateOpt.get().getContext();
            return context.stream()
                    .map(this::toMessageVO)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("读取对话历史失败: userId={}, conversationId={}", userId, conversationId, e);
            return new ArrayList<>();
        }
    }

    // ==================== 私有方法 ====================

    /**
     * 获取会话并校验归属（不存在则抛异常）
     */
    private ChatConversationDO getAndCheckConversation(Long userId, String conversationId) {
        LambdaQueryWrapper<ChatConversationDO> queryWrapper = new LambdaQueryWrapper<ChatConversationDO>()
                .eq(ChatConversationDO::getUserId, userId)
                .eq(ChatConversationDO::getConversationId, conversationId);
        ChatConversationDO conversation = chatConversationMapper.selectOne(queryWrapper);
        if (conversation == null) {
            throw new BusinessException(ErrorCode.CONVERSATION_ID_NOT_FOUND);
        }
        return conversation;
    }

    /**
     * DO → VO 转换
     */
    private ConversationVO toConversationVO(ChatConversationDO conv) {
        ConversationVO vo = new ConversationVO();
        vo.setConversationId(conv.getConversationId());
        vo.setConversationName(conv.getConversationName());
        vo.setCreateTime(conv.getCreateTime() != null ? conv.getCreateTime().toString() : null);
        vo.setLastModelId(conv.getLastModelId());
        return vo;
    }

    /**
     * Msg → MessageVO 转换
     */
    private MessageVO toMessageVO(Msg msg) {
        MessageVO vo = new MessageVO();
        vo.setRole(msg.getRole().name().toLowerCase());
        vo.setContent(msg.getTextContent());
        vo.setChartData(extractChartData(msg.getTextContent()));
        vo.setTimestamp(parseTimestamp(msg.getTimestamp()));

        // 提取图片内容
        List<ImageBlock> imageBlocks = msg.getContentBlocks(ImageBlock.class);
        if (imageBlocks != null && !imageBlocks.isEmpty()) {
            List<String> urls = new ArrayList<>();
            for (ImageBlock block : imageBlocks) {
                Source source = block.getSource();
                if (source instanceof URLSource urlSource) {
                    urls.add(urlSource.getUrl());
                } else if (source instanceof Base64Source base64Source) {
                    // 转为 data URL，前端可直接用于 <img src>
                    urls.add("data:" + base64Source.getMediaType() + ";base64," + base64Source.getData());
                }
            }
            if (!urls.isEmpty()) {
                vo.setImageUrls(urls);
            }
        }

        return vo;
    }

    /**
     * 将时间戳字符串转换为 LocalDateTime
     */
    private LocalDateTime parseTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(timestamp, TS_FORMATTER);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从消息文本中提取图表数据 JSON（chartType 开头的 JSON 对象）
     */
    private String extractChartData(String text) {
        if (text == null || text.isBlank()) return null;
        int start = text.indexOf("{\"chartType\"");
        if (start < 0) start = text.indexOf("{\"charttype\"");
        if (start < 0) return null;
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) {
                String json = text.substring(start, i + 1);
                return json.contains("echartsOption") ? json : null;
            }}
        }
        return null;
    }

    /**
     * 根据首条消息生成会话名称（截取前 20 字符）
     */
    private String generateConversationName(String message) {
        if (message == null || message.isBlank()) {
            return "新对话";
        }
        String trimmed = message.trim();
        return trimmed.length() <= 20 ? trimmed : trimmed.substring(0, 20) + "...";
    }
}
