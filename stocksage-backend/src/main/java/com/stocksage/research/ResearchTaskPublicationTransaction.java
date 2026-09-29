package com.stocksage.research;

import com.stocksage.repository.UserAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 研究任务结果与 owner-fenced 终态 CAS 的原子发布边界。
 *
 * <p>编排决策留在调用方，本类只接收必须同事务提交的数据库工作。任一运行时失败，
 * 包括 owner CAS 拒绝，都会回滚报告、消息和任务终态整组写入。</p>
 */
@Service
public class ResearchTaskPublicationTransaction {

    /** 对用户行加悲观锁，串行同一用户的报告版本分配与发布。 */
    private final UserAccountRepository userAccountRepository;

    /** @param userAccountRepository 用户行锁仓储。 */
    public ResearchTaskPublicationTransaction(UserAccountRepository userAccountRepository) {
        this.userAccountRepository = userAccountRepository;
    }

    /**
     * 在单事务内执行并返回发布结果。
     *
     * @param publication 必须原子提交的数据库回调
     * @return 回调结果
     */
    @Transactional
    public <T> T execute(Supplier<T> publication) {
        return Objects.requireNonNull(publication, "publication").get();
    }

    /**
     * 在单事务内执行无返回值发布回调。
     *
     * @param publication 必须原子提交的数据库回调
     */
    @Transactional
    public void execute(Runnable publication) {
        Objects.requireNonNull(publication, "publication").run();
    }

    /**
     * 在稳定的数据库互斥锁下执行同一用户的报告、消息和任务发布。
     *
     * <p>回调开始前锁定用户行，只串行同一用户的发布，避免报告快照去重和 ticker 版本号分配
     * 并发触发唯一键冲突并把事务标记为 rollback-only。</p>
     *
     * @param userId 报告所属用户
     * @param publication 原子发布回调
     * @return 回调结果
     */
    @Transactional
    public <T> T executeForUser(String userId, Supplier<T> publication) {
        String normalizedUserId = requireUserId(userId);
        Supplier<T> requiredPublication = Objects.requireNonNull(publication, "publication");
        // 调用 SELECT ... FOR UPDATE 锁定用户行，作为同用户发布的稳定互斥点。
        userAccountRepository.lockByUserIdForUpdate(normalizedUserId)
                .orElseThrow(() -> new IllegalStateException(
                        "publication user does not exist: " + normalizedUserId));
        return requiredPublication.get();
    }

    /**
     * 在用户行锁和单事务内执行无返回值发布回调。
     *
     * @param userId 报告所属用户
     * @param publication 原子发布回调
     */
    @Transactional
    public void executeForUser(String userId, Runnable publication) {
        Runnable requiredPublication = Objects.requireNonNull(publication, "publication");
        executeForUser(userId, () -> {
            requiredPublication.run();
            return null;
        });
    }

    /** 校验并归一化发布用户 ID。 */
    private String requireUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("publication user id is required");
        }
        return userId.trim();
    }
}
