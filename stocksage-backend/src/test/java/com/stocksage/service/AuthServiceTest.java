package com.stocksage.service;

import com.stocksage.exception.DuplicateEmailException;
import com.stocksage.model.entity.User;
import com.stocksage.model.entity.UserProfile;
import com.stocksage.repository.UserAccountRepository;
import com.stocksage.repository.UserProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserAccountRepository userAccountRepository;

    @Mock
    private UserProfileRepository userProfileRepository;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userAccountRepository, userProfileRepository, passwordEncoder);
    }

    @Test
    void registerPersistsUserWithBcryptHashAndDefaultProfile() {
        when(userAccountRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(userAccountRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User user = authService.register(" New@Example.com ", "demo1234");

        assertThat(user.getUserId()).startsWith("u_").hasSize(14);
        assertThat(user.getEmail()).isEqualTo("new@example.com");
        assertThat(user.getPasswordHash()).isNotEqualTo("demo1234");
        assertThat(passwordEncoder.matches("demo1234", user.getPasswordHash())).isTrue();
        assertThat(user.isEmailVerified()).isTrue();

        ArgumentCaptor<UserProfile> profileCaptor = ArgumentCaptor.forClass(UserProfile.class);
        verify(userProfileRepository).save(profileCaptor.capture());
        assertThat(profileCaptor.getValue().getUserId()).isEqualTo(user.getUserId());
        assertThat(profileCaptor.getValue().getWatchList()).isEqualTo("[]");
    }

    @Test
    void registerRejectsDuplicateEmail() {
        when(userAccountRepository.existsByEmail("dupe@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register("dupe@example.com", "demo1234"))
                .isInstanceOf(DuplicateEmailException.class);

        verify(userAccountRepository, never()).save(any());
        verify(userProfileRepository, never()).save(any());
    }

    @Test
    void registerRejectsShortPassword() {
        assertThatThrownBy(() -> authService.register("new@example.com", "short"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 8");
    }
}
