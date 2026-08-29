package com.stocksage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.memory.LongTermMemory;
import com.stocksage.memory.LongTermMemoryProperties;
import com.stocksage.model.entity.UserMemoryFact;
import com.stocksage.model.entity.UserProfile;
import com.stocksage.repository.UserMemoryFactRepository;
import com.stocksage.repository.UserProfileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserLongTermMemoryForgettingTest {

    private static final String USER_ID = "memory-user";
    private static final Instant NOW_INSTANT = Instant.parse("2026-08-29T00:00:00Z");
    private static final LocalDateTime NOW = LocalDateTime.ofInstant(NOW_INSTANT, ZoneOffset.UTC);

    @Test
    void forgetsThenReconfirmsAndExplicitlyRevokesHolding() {
        UserProfileRepository profileRepository = mock(UserProfileRepository.class);
        UserMemoryFactRepository factRepository = mock(UserMemoryFactRepository.class);
        UserProfile profile = profile();
        List<UserMemoryFact> facts = new ArrayList<>();
        UserMemoryFact holding = holding(NOW.minusDays(60));
        facts.add(holding);
        UserMemoryFact risk = memoryFact(
                2L, UserMemoryFact.FactType.RISK_PREFERENCE,
                "risk_preference", "aggressive", NOW.minusDays(360));
        facts.add(risk);
        facts.add(memoryFact(
                3L, UserMemoryFact.FactType.PROFILE_SUMMARY,
                "profile_summary", "old summary", NOW.minusDays(181)));

        when(profileRepository.findById(USER_ID)).thenReturn(Optional.of(profile));
        when(profileRepository.save(any(UserProfile.class))).thenAnswer(call -> call.getArgument(0));
        when(factRepository.findByUserIdAndRevokedAtIsNullOrderByIdAsc(USER_ID))
                .thenAnswer(call -> facts.stream().filter(fact -> fact.getRevokedAt() == null).toList());
        when(factRepository.confirm(any(), any(), any(), any(), any(), any()))
                .thenAnswer(call -> {
                    UserMemoryFact fact = fact(facts, call.getArgument(1), call.getArgument(2));
                    fact.setFactValue(call.getArgument(3));
                    fact.setLastConfirmedAt(call.getArgument(4));
                    fact.setSourceMessageId(call.getArgument(5));
                    fact.setRevokedAt(null);
                    return 1;
                });
        when(factRepository.revokeHolding(any(), any(), any(), any(), any()))
                .thenAnswer(call -> {
                    UserMemoryFact fact = fact(facts, UserMemoryFact.FactType.HOLDING.name(),
                            call.getArgument(1));
                    fact.setFactValue(call.getArgument(2));
                    fact.setRevokedAt(call.getArgument(3));
                    fact.setSourceMessageId(call.getArgument(4));
                    return 1;
                });

        LongTermMemoryProperties properties = new LongTermMemoryProperties();
        UserService userService = new UserService(
                profileRepository,
                factRepository,
                new ObjectMapper(),
                properties,
                Clock.fixed(NOW_INSTANT, ZoneOffset.UTC)
        );
        LongTermMemory memory = new LongTermMemory(
                userService, mock(ChatClient.class), new ObjectMapper());

        assertThat(memory.buildPromptContext(USER_ID))
                .contains("AAPL", "NVDA", "aggressive")
                .doesNotContain("old summary");

        holding.setLastConfirmedAt(NOW.minusDays(61));
        risk.setLastConfirmedAt(NOW.minusDays(361));
        assertThat(memory.buildPromptContext(USER_ID))
                .contains("NVDA")
                .doesNotContain("AAPL", "aggressive", "moderate", "old summary");

        memory.extractAndUpdate(USER_ID, "", "I hold AAPL", 42L, NOW);
        assertThat(holding.getLastConfirmedAt()).isEqualTo(NOW);
        assertThat(holding.getSourceMessageId()).isEqualTo(42L);
        assertThat(holding.getRevokedAt()).isNull();
        assertThat(memory.buildPromptContext(USER_ID)).contains("AAPL");

        memory.extractAndUpdate(USER_ID, "", "I sold one AAPL share", 43L, NOW.plusMinutes(1));
        assertThat(holding.getRevokedAt()).isNull();

        memory.extractAndUpdate(USER_ID, "", "I no longer hold AAPL", 44L, NOW.plusMinutes(2));
        assertThat(holding.getRevokedAt()).isEqualTo(NOW.plusMinutes(2));
        assertThat(holding.getSourceMessageId()).isEqualTo(44L);
        assertThat(memory.buildPromptContext(USER_ID))
                .contains("NVDA")
                .doesNotContain("AAPL");
    }

    private UserProfile profile() {
        UserProfile profile = new UserProfile();
        profile.setUserId(USER_ID);
        profile.setHoldings("[\"AAPL\"]");
        profile.setWatchList("[\"NVDA\"]");
        profile.setRiskPreference("moderate");
        profile.setProfileSummary("");
        return profile;
    }

    private UserMemoryFact holding(LocalDateTime confirmedAt) {
        return memoryFact(1L, UserMemoryFact.FactType.HOLDING, "AAPL", "AAPL", confirmedAt);
    }

    private UserMemoryFact memoryFact(
            Long id,
            UserMemoryFact.FactType type,
            String key,
            String value,
            LocalDateTime confirmedAt
    ) {
        UserMemoryFact fact = new UserMemoryFact();
        fact.setId(id);
        fact.setUserId(USER_ID);
        fact.setFactType(type);
        fact.setFactKey(key);
        fact.setFactValue(value);
        fact.setLastConfirmedAt(confirmedAt);
        return fact;
    }

    private UserMemoryFact fact(List<UserMemoryFact> facts, String factType, String factKey) {
        UserMemoryFact.FactType type = UserMemoryFact.FactType.valueOf(factType);
        return facts.stream()
                .filter(fact -> fact.getFactType() == type && fact.getFactKey().equals(factKey))
                .findFirst()
                .orElseGet(() -> {
                    UserMemoryFact fact = new UserMemoryFact();
                    fact.setId((long) facts.size() + 1L);
                    fact.setUserId(USER_ID);
                    fact.setFactType(type);
                    fact.setFactKey(factKey);
                    facts.add(fact);
                    return fact;
                });
    }
}
