package com.stocksage.repository;

import com.stocksage.model.entity.InvestmentReportVersion;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 版本化投资报告仓储。
 *
 * <p>所有面向用户的读取都把 userId 放进查询条件，避免仅凭主键越权访问；
 * 快照与上下文查询实现精确缓存复用，悲观锁查询用于串行化审核和研究记忆捕获。</p>
 */
public interface InvestmentReportVersionRepository extends JpaRepository<InvestmentReportVersion, Long> {

    /**
     * 按报告主键和所有者读取版本。
     *
     * @param id 报告版本主键
     * @param userId 当前用户标识
     * @return 用户拥有的报告；不存在或不属于该用户时为空
     */
    Optional<InvestmentReportVersion> findByIdAndUserId(Long id, String userId);

    /**
     * 按报告主键和所有者读取并获取悲观写锁。
     *
     * <p>必须在事务中调用，锁保持到事务结束；审核状态变更和研究记忆捕获借此串行，
     * 防止被否决的报告同时进入记忆。</p>
     *
     * @param id 报告版本主键
     * @param userId 当前用户标识
     * @return 已锁定的用户报告；不存在或越权时为空
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select report
            from InvestmentReportVersion report
            where report.id = :id and report.userId = :userId
            """)
    Optional<InvestmentReportVersion> findByIdAndUserIdForUpdate(
            @Param("id") Long id,
            @Param("userId") String userId
    );

    /**
     * 查找同一用户、标的、数据快照和问题上下文的唯一报告。
     *
     * <p>命中只表示输入相同；服务层仍会重新检查机器门禁和人工审核状态后才允许复用。</p>
     *
     * @param userId 报告所属用户
     * @param ticker 已归一化的证券代码
     * @param dataSnapshotHash 证据快照哈希
     * @param contextHash 用户问题与数据的上下文哈希
     * @return 精确匹配的报告版本；没有时为空
     */
    Optional<InvestmentReportVersion> findByUserIdAndTickerAndDataSnapshotHashAndContextHash(
            String userId,
            String ticker,
            String dataSnapshotHash,
            String contextHash
    );

    /**
     * 读取用户某标的业务版本号最大的报告，用于计算下一版本号。
     *
     * @param userId 报告所属用户
     * @param ticker 已归一化的证券代码
     * @return 当前最高版本；该标的尚无报告时为空
     */
    Optional<InvestmentReportVersion> findTopByUserIdAndTickerOrderByReportVersionDesc(
            String userId,
            String ticker
    );

    /**
     * 分页读取用户全部报告，数据库创建时间新的在前。
     *
     * @param userId 报告所属用户
     * @param pageable 页码和每页数量
     * @return 当前页报告；没有数据时为空列表
     */
    List<InvestmentReportVersion> findByUserIdOrderByCreatedAtDesc(
            String userId,
            Pageable pageable
    );

    /**
     * 分页读取用户某标的的版本历史，业务版本号大的在前。
     *
     * @param userId 报告所属用户
     * @param ticker 已归一化的证券代码
     * @param pageable 页码和每页数量
     * @return 当前页版本历史；没有数据时为空列表
     */
    List<InvestmentReportVersion> findByUserIdAndTickerOrderByReportVersionDesc(
            String userId,
            String ticker,
            Pageable pageable
    );
}
