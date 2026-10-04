package com.stocksage.controller;

import com.stocksage.model.dto.RagEvalRequest;
import com.stocksage.model.dto.RagEvalResponse;
import com.stocksage.service.RagEvalService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仅供 evals 中的 RAG 脚本使用的评估接口。
 *
 * <p>这些接口返回比对话界面更丰富的诊断载荷，
 * 包括检索上下文、引用信息和中间检索细节。</p>
 */
@RestController
@RequestMapping("/api/eval")
@RequiredArgsConstructor
public class RagEvalController {

    /** 串联检索、回答生成和诊断信息组装。 */
    private final RagEvalService ragEvalService;

    /**
     * 执行一次 RAG 离线评测请求。
     *
     * <p>该接口面向 evals 中的 RAG 脚本而非普通前端聊天页面，返回值会包含检索上下文、引用和可选中间阶段，
     * 用于定位召回、重排或回答生成环节的质量问题。</p>
     *
     * @param request 问题、过滤条件和诊断开关
     * @return 生成答案、引用及各检索阶段结果
     */
    @PostMapping("/rag")
    public RagEvalResponse evaluateRag(@Valid @RequestBody RagEvalRequest request) {
        return ragEvalService.evaluate(request);
    }
}
