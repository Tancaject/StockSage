package com.stocksage.identity;

import com.stocksage.model.entity.User;
import com.stocksage.security.AuthenticatedUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestIdentityTest {

    private final RequestIdentity requestIdentity = new RequestIdentity();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void currentUserIdReadsAuthenticatedPrincipalUserId() {
        User user = new User();
        user.setUserId("u_real_123");
        user.setEmail("real@example.com");
        user.setPasswordHash("hash");
        user.setEmailVerified(true);
        AuthenticatedUser principal = new AuthenticatedUser(user);

        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));

        assertThat(requestIdentity.currentUserId()).isEqualTo("u_real_123");
    }

    @Test
    void currentUserIdThrowsWhenNoAuthenticationExists() {
        assertThatThrownBy(requestIdentity::currentUserId)
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }

    @Test
    void currentUserIdThrowsForAnonymousPrincipalInsteadOfLeakingDemoAccount() {
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("key", "anonymousUser",
                        AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        assertThatThrownBy(requestIdentity::currentUserId)
                .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
    }
}
