package com.stocksage.controller;

import com.stocksage.service.AgentAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
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
     * 获取能力、MCP 与 Skill 执行状态。
     *
     * @return 脱敏后的 Agent 运行快照
     */
    @GetMapping("/runtime")
    public AgentAdminService.RuntimeSnapshot runtime() {
        return agentAdminService.runtime();
    }
}
