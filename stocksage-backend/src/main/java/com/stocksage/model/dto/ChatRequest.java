package com.stocksage.model.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 对话请求 DTO。
 * 前端 POST /api/chat/stream 时发送此结构。
 */
@Data
public class ChatRequest {

    /** 后端注入的会话用户 ID；保留字段兼容旧前端。 */
    private String userId;

    /** 一次主动提交的客户端身份；网络重发沿用，重新生成使用新值。缺省保留旧幂等键语义。 */
    @jakarta.validation.constraints.Pattern(regexp = "[A-Za-z0-9_-]{1,80}",
            message = "submissionId 须为 1–80 位字母、数字、下划线或连字符")
    private String submissionId;

    /** 会话 ID，首次对话可为 null（后端自动创建） */
    private Long conversationId;

    /** 会话来源。chat 会显示在聊天侧栏；workbench 归属于工作台运行记录。 */
    private String origin = "chat";

    /** 新建会话时的可选标题；工作台用中文任务名避免从内部 prompt 生成标题。 */
    private String title;

    /** 用户输入的自然语言问题；纯图片请求可由后端补成默认读图指令。 */
    @Size(max = 12000, message = "消息不能超过 12000 个字符，请缩短后重试")
    private String message;

    /** 当前轮随问题提交的图片。原图只用于本轮模型调用，不写入长期历史。 */
    private List<ImageAttachment> images;

    /**
     * 前端执行“重新生成”或“编辑后发送”时置为 true。
     * 后端会先删除当前会话的最后一轮用户/助手消息，再保存新消息，
     * 避免本地界面已替换但持久化历史仍污染下一轮提示词。
     */
    private Boolean replaceLastTurn;

    /**
     * 当前聊天轮次上传或粘贴的图片附件。
     *
     * <p>后端校验 MIME 类型与 data URL 后把图片传给多模态模型，不把原图写入消息表。</p>
     */
    @Data
    public static class ImageAttachment {

        /** 展示用文件名。 */
        private String name;

        /** MIME 类型，例如 image/png。 */
        private String mediaType;

        /** data URL，格式为 data:image/png;base64,...；后端会校验类型、编码和大小。 */
        private String dataUrl;
    }
}
