package com.stocksage.identity;

import com.stocksage.exception.DuplicateEmailException;
import com.stocksage.model.entity.User;
import com.stocksage.model.entity.UserProfile;
import com.stocksage.repository.UserAccountRepository;
import com.stocksage.repository.UserProfileRepository;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 轻量账号注册服务。
 *
 * <p>注册会在同一事务内创建 {@link User} 与默认 {@link UserProfile}，密码只以编码后哈希落库。
 * 登录和会话校验由 Spring Security 层负责，本类不签发或解析会话。</p>
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    /** 项目接受的最小邮箱格式校验规则。 */
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    /** 随机用户 ID 使用的字符表。 */
    private static final char[] BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    /** 生成不可预测用户 ID 的密码学安全随机源。 */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 读写账号主表。 */
    private final UserAccountRepository userAccountRepository;
    /** 为新账号创建默认投资画像。 */
    private final UserProfileRepository userProfileRepository;
    /** 把明文密码转换为不可逆哈希。 */
    private final PasswordEncoder passwordEncoder;

    /**
     * 注册账号并创建默认画像。
     *
     * @param email 用户邮箱；保存前转小写并去除首尾空格
     * @param rawPassword 明文密码，至少 8 个字符
     * @return 已持久化的账号实体
     * @throws DuplicateEmailException 邮箱已存在或并发注册触发唯一约束时
     * @throws IllegalArgumentException 邮箱或密码格式不合法时
     */
    @Transactional
    public User register(String email, String rawPassword) {
        String normalizedEmail = normalizeEmail(email);
        validateEmail(normalizedEmail);
        validatePassword(rawPassword);

        if (userAccountRepository.existsByEmail(normalizedEmail)) {
            throw new DuplicateEmailException("Email is already registered");
        }

        User user = new User();
        user.setUserId(generateUserId());
        user.setEmail(normalizedEmail);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setEmailVerified(true);
        user.setNickname(defaultNickname(normalizedEmail));

        try {
            // 先调用账号仓储写入唯一邮箱，再用实际 userId 创建一对一默认画像。
            User saved = userAccountRepository.save(user);
            userProfileRepository.save(defaultProfile(saved.getUserId()));
            return saved;
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateEmailException("Email is already registered");
        }
    }

    /**
     * 归一化邮箱以保证登录和唯一性检查口径一致。
     *
     * @param email 原始邮箱
     * @return 去空格并转小写的邮箱；null 返回空串
     */
    public String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    /** 校验归一化邮箱是否符合项目的最小格式要求。 */
    private void validateEmail(String email) {
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            throw new IllegalArgumentException("Invalid email address");
        }
    }

    /** 校验注册密码的最小长度。 */
    private void validatePassword(String rawPassword) {
        if (rawPassword == null || rawPassword.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters");
        }
    }

    /** 生成带 {@code u_} 前缀且当前数据库中未占用的用户 ID。 */
    private String generateUserId() {
        for (int attempt = 0; attempt < 8; attempt++) {
            String userId = "u_" + randomBase62(12);
            if (!userAccountRepository.existsById(userId)) {
                return userId;
            }
        }
        throw new IllegalStateException("Unable to allocate user id");
    }

    /** 从 BASE62 字符表生成指定长度的安全随机文本。 */
    private String randomBase62(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(BASE62[RANDOM.nextInt(BASE62.length)]);
        }
        return builder.toString();
    }

    /** 默认昵称取邮箱 @ 前部分，异常输入则使用通用名称。 */
    private String defaultNickname(String email) {
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : "StockSage User";
    }

    /** 为新账号构造空持仓、空关注和中等风险偏好的默认画像。 */
    private UserProfile defaultProfile(String userId) {
        UserProfile profile = new UserProfile();
        profile.setUserId(userId);
        profile.setHoldings("[]");
        profile.setWatchList("[]");
        profile.setRiskPreference("moderate");
        profile.setProfileSummary("");
        return profile;
    }
}
