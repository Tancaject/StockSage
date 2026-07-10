package com.stocksage.service;

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

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final char[] BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserAccountRepository userAccountRepository;
    private final UserProfileRepository userProfileRepository;
    private final PasswordEncoder passwordEncoder;

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
            User saved = userAccountRepository.save(user);
            userProfileRepository.save(defaultProfile(saved.getUserId()));
            return saved;
        } catch (DataIntegrityViolationException ex) {
            throw new DuplicateEmailException("Email is already registered");
        }
    }

    public String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private void validateEmail(String email) {
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            throw new IllegalArgumentException("Invalid email address");
        }
    }

    private void validatePassword(String rawPassword) {
        if (rawPassword == null || rawPassword.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters");
        }
    }

    private String generateUserId() {
        for (int attempt = 0; attempt < 8; attempt++) {
            String userId = "u_" + randomBase62(12);
            if (!userAccountRepository.existsById(userId)) {
                return userId;
            }
        }
        throw new IllegalStateException("Unable to allocate user id");
    }

    private String randomBase62(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(BASE62[RANDOM.nextInt(BASE62.length)]);
        }
        return builder.toString();
    }

    private String defaultNickname(String email) {
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : "StockSage User";
    }

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
