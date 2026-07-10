package com.stocksage.service;

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

    private static final Set<String> SUPPORTED_IMAGE_MEDIA_TYPES = Set.of(
            "image/png", "image/jpeg", "image/webp"
    );

    @Value("${stocksage.chat.multimodal.enabled:true}")
    private boolean multimodalEnabled;

    @Value("${stocksage.chat.multimodal.max-images:4}")
    private int maxImagesPerRequest;

    @Value("${stocksage.chat.multimodal.max-image-bytes:5242880}")
    private int maxImageBytes;

    /**
     * 归一化当前轮图片附件，并把 data URL 转为 Spring AI 可消费的媒体内容。
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
     */
    private String normalizeImageMediaType(String mediaType) {
        String normalized = mediaType == null ? "" : mediaType.trim().toLowerCase(Locale.ROOT);
        return "image/jpg".equals(normalized) ? "image/jpeg" : normalized;
    }

    /**
     * 清理进入模型请求元数据的附件名。
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
