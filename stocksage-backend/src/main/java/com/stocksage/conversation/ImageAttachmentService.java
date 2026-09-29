package com.stocksage.conversation;

import com.stocksage.model.dto.ChatRequest;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 聊天多模态输入的归一化与校验。
 *
 * <p>把前端上传的 data URL 图片附件解码成 Spring AI 可消费的 {@link Media}，
 * 并对数量、大小、MIME 类型做边界校验。从 ChatService 拆出，职责单一。</p>
 */
@Service
public class ImageAttachmentService {

    /** 当前模型链路允许的图片 MIME 类型白名单。 */
    private static final Set<String> SUPPORTED_IMAGE_MEDIA_TYPES = Set.of(
            "image/png", "image/jpeg", "image/webp"
    );

    /** 多模态总开关；关闭时有附件的请求会明确失败。 */
    @Value("${stocksage.chat.multimodal.enabled:true}")
    private boolean multimodalEnabled;

    /** 单轮最多接受的图片数量。 */
    @Value("${stocksage.chat.multimodal.max-images:4}")
    private int maxImagesPerRequest;

    /** 单张解码后图片的最大字节数。 */
    @Value("${stocksage.chat.multimodal.max-image-bytes:5242880}")
    private int maxImageBytes;

    /**
     * 归一化当前轮图片附件，并把 data URL 转为 Spring AI 可消费的媒体内容。
     *
     * @param attachments 前端提交的图片附件
     * @return 已校验并解码的 Spring AI Media 列表
     * @throws IllegalArgumentException 功能关闭、数量/类型/大小或 base64 不合法时
     */
    public List<Media> normalizeImageAttachments(List<ChatRequest.ImageAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }
        if (!multimodalEnabled) {
            throw new IllegalArgumentException("多模态输入当前未启用。");
        }
        if (attachments.size() > maxImagesPerRequest) {
            throw new IllegalArgumentException("一次最多上传 " + maxImagesPerRequest + " 张图片。");
        }

        List<Media> media = new ArrayList<>();
        for (ChatRequest.ImageAttachment attachment : attachments) {
            if (attachment == null) {
                continue;
            }
            DecodedImage decoded = decodeImageAttachment(attachment);
            media.add(Media.builder()
                    .mimeType(decoded.mimeType())
                    .data(decoded.bytes())
                    .name(sanitizeAttachmentName(attachment.getName()))
                    .build());
        }
        return media;
    }

    /**
     * 允许纯图片请求；无文本且无图片时明确拒绝。
     *
     * @param message 用户文本
     * @param hasImages 当前请求是否包含有效图片
     * @return 去空格文本；纯图片请求返回默认分析指令
     */
    public String normalizeUserMessage(String message, boolean hasImages) {
        String normalized = message == null ? "" : message.trim();
        if (!normalized.isBlank()) {
            return normalized;
        }
        if (hasImages) {
            return "请分析这张图片。";
        }
        throw new IllegalArgumentException("消息内容不能为空。");
    }

    /**
     * 从前端 data URL 中提取 MIME 类型和二进制图片。
     *
     * @param attachment 单张前端附件
     * @return 已校验的 MIME 类型与字节数组
     */
    private DecodedImage decodeImageAttachment(ChatRequest.ImageAttachment attachment) {
        String dataUrl = attachment.getDataUrl() == null ? "" : attachment.getDataUrl().trim();
        if (dataUrl.isBlank()) {
            throw new IllegalArgumentException("图片数据不能为空。");
        }

        String mediaType = normalizeImageMediaType(attachment.getMediaType());
        String base64 = dataUrl;
        if (dataUrl.regionMatches(true, 0, "data:", 0, 5)) {
            int commaIndex = dataUrl.indexOf(',');
            if (commaIndex < 0) {
                throw new IllegalArgumentException("图片 data URL 格式不正确。");
            }
            String header = dataUrl.substring(5, commaIndex);
            int semicolonIndex = header.indexOf(';');
            String headerMediaType = semicolonIndex >= 0 ? header.substring(0, semicolonIndex) : header;
            if (!header.toLowerCase(Locale.ROOT).contains("base64")) {
                throw new IllegalArgumentException("图片必须使用 base64 data URL。");
            }
            if (mediaType.isBlank()) {
                mediaType = normalizeImageMediaType(headerMediaType);
            }
            base64 = dataUrl.substring(commaIndex + 1);
        }

        if (mediaType.isBlank()) {
            throw new IllegalArgumentException("图片 MIME 类型不能为空。");
        }
        if (!SUPPORTED_IMAGE_MEDIA_TYPES.contains(mediaType)) {
            throw new IllegalArgumentException("暂不支持该图片类型：" + mediaType);
        }

        byte[] bytes;
        try {
            // 调用 JDK Base64 解码器，把 data URL 正文转换成模型请求所需的二进制。
            bytes = Base64.getDecoder().decode(base64.replaceAll("\\s+", ""));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("图片 base64 数据无法解析。");
        }
        if (bytes.length == 0) {
            throw new IllegalArgumentException("图片不能为空。");
        }
        if (bytes.length > maxImageBytes) {
            throw new IllegalArgumentException("单张图片不能超过 " + formatBytes(maxImageBytes) + "。");
        }
        return new DecodedImage(MimeTypeUtils.parseMimeType(mediaType), bytes);
    }

    /**
     * 归一化图片 MIME 类型。
     *
     * @param mediaType 前端或 data URL 声明的类型
     * @return 小写标准类型；image/jpg 转为 image/jpeg
     */
    private String normalizeImageMediaType(String mediaType) {
        String normalized = mediaType == null ? "" : mediaType.trim().toLowerCase(Locale.ROOT);
        return "image/jpg".equals(normalized) ? "image/jpeg" : normalized;
    }

    /**
     * 清理进入模型请求元数据的附件名。
     *
     * @param name 原始文件名
     * @return 去除控制字符和路径非法字符的短名称
     */
    private String sanitizeAttachmentName(String name) {
        String sanitized = name == null ? "uploaded-image" : name.trim()
                .replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("[\\\\/:*?\"<>|]+", "_");
        if (sanitized.isBlank()) {
            return "uploaded-image";
        }
        return sanitized.length() > 80 ? sanitized.substring(0, 80) : sanitized;
    }

    /**
     * 面向错误消息的人类可读大小。
     *
     * @param bytes 字节数
     * @return B、KB 或 MB 文本
     */
    private String formatBytes(int bytes) {
        if (bytes >= 1024 * 1024) {
            return (bytes / (1024 * 1024)) + " MB";
        }
        if (bytes >= 1024) {
            return (bytes / 1024) + " KB";
        }
        return bytes + " B";
    }

    /**
     * 已校验的图片载荷。
     */
    private record DecodedImage(MimeType mimeType, byte[] bytes) {
    }
}
