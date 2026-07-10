package com.stocksage.repository;

import com.stocksage.model.entity.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 用户长期画像仓储。
 *
 * <p>画像内容来自对话中的可复用事实，用于后续个性化提示词和记忆上下文。
 * 主键通常就是用户标识，因此该仓储直接继承 JpaRepository 的标准增删改查能力。</p>
 */
public interface UserProfileRepository extends JpaRepository<UserProfile, String> {
}
