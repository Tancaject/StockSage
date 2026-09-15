package com.stocksage.repository;

import com.stocksage.model.entity.AgentTrace;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 智能体执行追踪仓储。
 *
 * <p>前端推理面板通过该仓储读取最近的对话链路，展示规划、工具调用和最终回答等执行过程。
 * 追踪记录以用户维度隔离，避免不同用户的研究轨迹互相混入。</p>
 */
public interface AgentTraceRepository extends JpaRepository<AgentTrace, String> {
}
