package com.stocksage.controller;

import com.stocksage.agent.AgentRuntimeConfiguration;
import com.stocksage.research.DeepEvidenceReplanService;
import com.stocksage.research.ResearchEvidenceSnapshotService;
import com.stocksage.service.AgentAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Agent 配置与运行状态的只读管理接口。
 *
 * <p>路由位于管理令牌保护范围内，供 Workbench 运维面板查看已注册 Skill、能力和运行指标；
 * 本控制器不执行 Agent 任务，也不修改配置。</p>
 */
@RestController
@RequestMapping("/api/admin/agent")
@RequiredArgsConstructor
public class AgentAdminController {

    /** 汇总 Skill 注册表与运行时观测数据。 */
    private final AgentAdminService agentAdminService;
    private final AgentRuntimeConfiguration runtimeConfiguration;
    private final DeepEvidenceReplanService replanService;
    private final ResearchEvidenceSnapshotService evidenceSnapshotService;

    @Value("${stocksage.chat.model-routing.fast-model:}")
    private String fastModel;
    @Value("${stocksage.chat.model-routing.standard-model:}")
    private String standardModel;
    @Value("${stocksage.chat.model-routing.strong-model:}")
    private String strongModel;
    @Value("${stocksage.chat.model-routing.vision-model:}")
    private String visionModel;
    @Value("${stocksage.chat.model-routing.max-output-tokens:0}")
    private int maxOutputTokens;
    @Value("${stocksage.chat.model-routing.standard-thinking-budget:0}")
    private int thinkingBudget;

    /**
     * 获取启动时加载的 Skill 定义和校验结果。
     *
     * @return 当前 Skill 注册快照
     */
    @GetMapping("/skills")
    public AgentAdminService.SkillsSnapshot skills() {
        return agentAdminService.skills();
    }

    /**
     * 获取能力、工具执行与端到端耗时。
     *
     * @return 脱敏后的 Agent 运行快照
     */
    @GetMapping("/runtime")
    public AgentAdminService.RuntimeSnapshot runtime() {
        return agentAdminService.runtime();
    }

    /**
     * 评测成绩单记录的运行配置身份：各角色客户端默认值（模型、输出上限、思考设置、系统提示词哈希）、
     * 模型分档、对话供应商（只记主机名和路径哈希）和 DEEP 取证循环配置。不含密钥。
     */
    @GetMapping("/model-config")
    public java.util.Map<String, Object> modelConfig() {
        return java.util.Map.of(
                "clients", runtimeConfiguration.snapshot(),
                "chatProvider", runtimeConfiguration.chatProviderSnapshot(),
                "modelRouting", java.util.Map.of("fast", fastModel, "standard", standardModel, "strong", strongModel,
                        "vision", visionModel, "maxOutputTokens", maxOutputTokens, "thinkingBudget", thinkingBudget),
                "deepEvidence", replanService.runtimeConfiguration());
    }

    /** DEEP 任务的最终证据快照（辩论和报告模型实际读到的证据原文），供证据覆盖评审离线复现。 */
    @GetMapping("/research-tasks/{taskId}/evidence-snapshot")
    public ResponseEntity<String> evidenceSnapshot(@PathVariable Long taskId) {
        return evidenceSnapshotService.finalSnapshotJson(taskId)
                .map(json -> ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
