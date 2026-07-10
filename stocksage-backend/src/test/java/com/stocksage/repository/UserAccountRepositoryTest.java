package com.stocksage.repository;

import com.stocksage.model.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
class UserAccountRepositoryTest {

    @Autowired
    private UserAccountRepository repository;

    @Test
    void findByEmailReturnsSavedAccount() {
        User user = user("u_test_001", "investor@example.com");
        repository.saveAndFlush(user);

        assertThat(repository.findByEmail("investor@example.com"))
                .isPresent()
                .get()
                .extracting(User::getUserId)
                .isEqualTo("u_test_001");
        assertThat(repository.existsByEmail("investor@example.com")).isTrue();
    }

    @Test
    void duplicateEmailViolatesUniqueConstraint() {
        repository.saveAndFlush(user("u_test_001", "dupe@example.com"));

        assertThatThrownBy(() -> repository.saveAndFlush(user("u_test_002", "dupe@example.com")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private User user(String userId, String email) {
        User user = new User();
        user.setUserId(userId);
        user.setEmail(email);
        user.setPasswordHash("$2a$10$abcdefghijklmnopqrstuu2yWsrpXbntj1J6Zy2uKms8r6RZ0mC1e");
        user.setEmailVerified(true);
        user.setNickname("Tester");
        return user;
    }
}
