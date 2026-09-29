package com.stocksage.repository;

import com.stocksage.model.entity.AgentTrace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 智能体执行追踪仓储。
 *
 * <p>前端推理面板通过该仓储读取最近的对话链路，展示规划、工具调用和最终回答等执行过程。
 * 追踪记录以用户维度隔离，避免不同用户的研究轨迹互相混入。</p>
 */
public interface AgentTraceRepository extends JpaRepository<AgentTrace, String> {
    /** 管理指标只取摘要；先限制最近样本，再由调用方筛选成功状态，保持统计口径。 */
    @Query("select t.status as status, t.createdAt as createdAt, t.durationMs as durationMs from AgentTrace t")
    List<LatencySample> findLatencySamples(Pageable pageable);

    interface LatencySample {
        String getStatus();
        LocalDateTime getCreatedAt();
        Long getDurationMs();
    }
}
