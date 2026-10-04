package com.stocksage.controller;

import com.stocksage.identity.RequestIdentity;
import com.stocksage.model.dto.UserProfileDTO;
import com.stocksage.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 用户控制器。
 * 提供用户长期画像的查询接口（持仓、关注列表、风险偏好）。
 * API 面只暴露 /me/*，不提供按 userId 寻址的画像端点，用户身份统一由会话主体解析。
 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    /** 读写用户长期画像与自选股。 */
    private final UserService userService;

    /** 从登录 Session 解析当前用户，API 不接受客户端自报 userId。 */
    private final RequestIdentity requestIdentity;

    /**
     * 获取当前登录用户的长期画像。
     *
     * @return 风险偏好、关注行业与自选股等画像数据
     */
    @GetMapping("/me/profile")
    public UserProfileDTO getCurrentUserProfile() {
        return userService.getUserProfile(currentUserId());
    }

    /**
     * 覆盖更新当前用户可编辑的画像字段。
     *
     * @param profile 已通过 Bean Validation 的画像内容
     * @return 持久化后的最新画像
     */
    @PutMapping("/me/profile")
    public UserProfileDTO updateCurrentUserProfile(@Valid @RequestBody UserProfileDTO profile) {
        return userService.updateUserProfile(currentUserId(), profile);
    }

    /**
     * 将股票代码加入当前用户自选股；重复添加保持幂等。
     *
     * @param ticker 待加入的股票代码
     */
    @PostMapping("/me/profile/watchlist/{ticker}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addToWatchList(@PathVariable String ticker) {
        userService.addToWatchList(currentUserId(), ticker);
    }

    /**
     * 从当前用户自选股移除股票代码；不存在时也返回成功。
     *
     * @param ticker 待移除的股票代码
     */
    @DeleteMapping("/me/profile/watchlist/{ticker}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeFromWatchList(@PathVariable String ticker) {
        userService.removeFromWatchList(currentUserId(), ticker);
    }

    /** 统一从安全上下文取用户 ID，避免各端点重复接收不可信参数。 */
    private String currentUserId() {
        return requestIdentity.currentUserId();
    }
}
