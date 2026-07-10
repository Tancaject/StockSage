package com.stocksage.controller;

import com.stocksage.config.RequestIdentity;
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

    private final UserService userService;
    private final RequestIdentity requestIdentity;

    @GetMapping("/me/profile")
    public UserProfileDTO getCurrentUserProfile() {
        return userService.getUserProfile(currentUserId());
    }

    @PutMapping("/me/profile")
    public UserProfileDTO updateCurrentUserProfile(@Valid @RequestBody UserProfileDTO profile) {
        return userService.updateUserProfile(currentUserId(), profile);
    }

    @PostMapping("/me/profile/watchlist/{ticker}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addToWatchList(@PathVariable String ticker) {
        userService.addToWatchList(currentUserId(), ticker);
    }

    @DeleteMapping("/me/profile/watchlist/{ticker}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeFromWatchList(@PathVariable String ticker) {
        userService.removeFromWatchList(currentUserId(), ticker);
    }

    private String currentUserId() {
        return requestIdentity.currentUserId();
    }
}
