package com.stocksage.repository;

import com.stocksage.model.entity.User;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 登录账号仓储。
 *
 * <p>除标准 JPA 操作外，提供邮箱唯一性检查和按用户加写锁的读取。</p>
 */
public interface UserAccountRepository extends JpaRepository<User, String> {

    /**
     * 按唯一登录邮箱查找账号。
     *
     * @param email 已规范化的邮箱
     * @return 匹配账号；不存在时为空
     */
    Optional<User> findByEmail(String email);

    /**
     * 判断邮箱是否已被占用，通常在注册前快速校验。
     *
     * @param email 已规范化的邮箱
     * @return 已存在同邮箱账号时为 true
     */
    boolean existsByEmail(String email);

    /**
     * 按用户 ID 读取账号并获取数据库悲观写锁。
     *
     * <p>必须在事务中调用；锁保持到事务提交或回滚，用于串行化依赖该用户行的状态变更。</p>
     *
     * @param userId 用户标识
     * @return 已锁定账号；用户不存在时为空
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select account from User account where account.userId = :userId")
    Optional<User> lockByUserIdForUpdate(@Param("userId") String userId);
}
