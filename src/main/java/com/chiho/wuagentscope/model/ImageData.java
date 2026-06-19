package com.chiho.wuagentscope.model;

import lombok.Data;

/**
 * 图片数据 DTO
 * <p>
 * 用于前端上传 Base64 编码的图片数据，支持多模态模型（如 qwen3-vl:8b）处理图片输入。
 *
 * @author ChiHo
 */
@Data
public class ImageData {

    /** Base64 编码的图片数据（不含 data:image/xxx;base64, 前缀） */
    private String base64;

    /** MIME 类型，如 image/png, image/jpeg, image/webp */
    private String mimeType;
}
