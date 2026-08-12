package com.stocksage.repository;

import com.stocksage.model.entity.ResearchTaskCheckpoint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * 深度研究任务检查点仓储。
 *
 * <p>检查点写入和删除前先锁定 {@code research_tasks} 真相行，确保任务接管、终态发布与
 * 快照变更按同一任务串行；业务层不能只按 checkpoint 主键盲写。</p>
 */
public interface ResearchTaskCheckpointRepository extends JpaRepository<ResearchTaskCheckpoint, Long> {

    /**
     * 锁定仍由指定租约持有的运行中任务，并验证数据库所有权。
     *
     * <p>必须在事务中调用；{@code SELECT ... FOR UPDATE} 的行锁保持到提交或回滚，
     * 使检查点保存无法与任务租约接管并发穿插。</p>
     *
     * @param taskId 研究任务主键
     * @param leaseToken 当前 worker 持有的租约令牌
     * @return 成功锁定时返回任务 ID；状态或令牌不匹配时为空
     */
    @Query(value = """
            SELECT id
              FROM research_tasks
             WHERE id = :taskId
               AND status = 'RUNNING'
               AND lease_token = :leaseToken
             FOR UPDATE
            """, nativeQuery = true)
    Optional<Long> lockOwnedRunningTask(
            @Param("taskId") Long taskId,
            @Param("leaseToken") String leaseToken
    );

    /**
     * 成功终态发布后锁定任务，再清理其检查点。
     *
     * <p>成功完成会清空租约令牌，因此终态清理改用 SUCCEEDED 状态作为围栏。
     * 必须在事务中调用，锁保持到事务结束。</p>
     *
     * @param taskId 研究任务主键
     * @return 成功锁定时返回任务 ID；任务不是 SUCCEEDED 时为空
     */
    @Query(value = """
            SELECT id
              FROM research_tasks
             WHERE id = :taskId
               AND status = 'SUCCEEDED'
             FOR UPDATE
            """, nativeQuery = true)
    Optional<Long> lockSucceededTask(@Param("taskId") Long taskId);

    /**
     * 读取任务当前唯一检查点。
     *
     * @param taskId 研究任务主键
     * @return 最新检查点；尚未保存或已清理时为空
     */
    Optional<ResearchTaskCheckpoint> findByTaskId(Long taskId);

    /**
     * 删除任务检查点。
     *
     * <p>调用方应先通过上述锁方法建立所有权或终态围栏，并在同一事务中执行删除。</p>
     *
     * @param taskId 研究任务主键
     */
    void deleteByTaskId(Long taskId);
}
