package com.chiho.wuagentscope.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 多模态聊天请求 DTO
 * <p>
 * 用于 POST 方式提交聊天请求，支持文本 + 图片混合输入。
 * 当模型支持 vision 能力时，前端可以通过 images 或 imageUrls 传递图片。
 *
 * @author ChiHo
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ChatRequest extends BaseRequest {

    /** 用户文本消息（可为空，纯图片场景） */
    private String message;

    /** 会话ID（前端生成的唯一标识，相同 ID 恢复历史对话） */
    private String chatId;

    /** 模型ID（可选，为空时使用默认模型） */
    private String modelId;

    /** 图片URL列表（网络图片地址） */
    private List<String> imageUrls;

    /** Base64 图片列表（前端上传的图片） */
    private List<ImageData> images;
}
