package com.stocksage.repository;

import com.stocksage.model.entity.AgentTrace;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 智能体执行追踪仓储。
 *
 * <p>前端推理面板通过该仓储读取最近的对话链路，展示规划、工具调用和最终回答等执行过程。
 * 追踪记录以用户维度隔离，避免不同用户的研究轨迹互相混入。</p>
 */
public interface AgentTraceRepository extends JpaRepository<AgentTrace, String> {

    /**
     * 按创建时间倒序分页读取某个用户的追踪记录。
     *
     * @param userId 用户标识
     * @param pageable 分页参数，通常只取最近若干条供前端展示
     * @return 最新追踪记录列表
     */
    List<AgentTrace> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);
}
