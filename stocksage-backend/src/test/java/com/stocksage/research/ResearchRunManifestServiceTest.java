package com.stocksage.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksage.agent.AgentRuntimeConfiguration;
import com.stocksage.capability.CapabilityRegistry;
import com.stocksage.capability.CapabilityDescriptor;
import com.stocksage.rag.RagService;
import com.stocksage.knowledge.EdgarIngestionService;
import com.stocksage.config.RuntimeArtifactIdentity;
import com.stocksage.config.ModelPricingProperties;
import com.stocksage.model.entity.ResearchTask;
import com.stocksage.repository.ResearchTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@DataJpaTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@Import({ResearchRunManifestService.class, ResearchRunManifestServiceTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ResearchRunManifestServiceTest {
    @Autowired ResearchTaskRepository repository;
    @Autowired ResearchRunManifestService service;
    @Autowired AgentRuntimeConfiguration configuration;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired CapabilityRegistry capabilities;
    @Autowired RagService rag;
    @Autowired DeepEvidenceReplanService replan;
    @Autowired EdgarIngestionService edgar;
    @Autowired RuntimeArtifactIdentity artifact;
    @Autowired ModelPricingProperties pricing;

    @BeforeEach
    void defaults() {
        reset(configuration, capabilities, rag, replan, edgar, artifact, pricing);
        when(pricing.snapshot()).thenReturn(Map.of("status", "UNCONFIGURED"));
        when(configuration.snapshot()).thenReturn(Map.of("manager", Map.of("model", "model-a")));
        when(configuration.chatProviderSnapshot()).thenReturn(Map.of("scope", "API_CONSTRUCTION", "host", "provider-a"));
        when(capabilities.descriptors()).thenReturn(List.of());
        when(rag.runtimeConfiguration()).thenReturn(Map.of("topK", 6));
        when(replan.runtimeConfiguration()).thenReturn(Map.of("enabled", false));
        when(edgar.runtimeConfiguration()).thenReturn(Map.of("scope", "CURRENT_INGESTION_DEFAULTS", "childSize", 800));
        when(artifact.snapshot()).thenReturn(Map.of("status", "KNOWN", "sha256", "artifact-a"));
    }

    @Test
    void freezesOnceAndSameConfigurationRetryRetainsOriginalBytes() throws Exception {
        ResearchTask task = task(1);
        service.verifyOrFreeze(task.getId(), "owner-a");
        String original = repository.readModelConfiguration(task.getId());
        var saved = objectMapper.readTree(original);
        assertThat(saved.path("schemaVersion").asInt()).isEqualTo(6);
        assertThat(saved.path("pricing").path("status").asText()).isEqualTo("UNCONFIGURED");
        assertThat(saved.path("chatProvider").path("host").asText()).isEqualTo("provider-a");
        assertThat(saved.path("scope").asText()).isEqualTo("EXECUTION_CONFIGURATION");
        assertThat(saved.path("configuration").path("manager").path("model").asText()).isEqualTo("model-a");
        assertThat(saved.path("rag").path("topK").asInt()).isEqualTo(6);
        assertThat(saved.path("policies").path("completion").path("sha256").asText()).matches("[a-f0-9]{64}");
        // A previously loaded entity must not overwrite the native-frozen column when saved.
        task.setAttempts(2);
        task.setLeaseToken("owner-b");
        repository.saveAndFlush(task);
        service.verifyOrFreeze(task.getId(), "owner-b");
        assertThat(repository.readModelConfiguration(task.getId())).isEqualTo(original);
    }

    @Test
    void changedConfigurationStopsWithoutOverwritingFirstManifest() {
        ResearchTask task = task(1);
        service.verifyOrFreeze(task.getId(), "owner-a");
        String original = repository.readModelConfiguration(task.getId());
        when(configuration.snapshot()).thenReturn(Map.of("manager", Map.of("model", "model-b")));
        assertRejected(task, "owner-a", "不一致");
        assertThat(repository.readModelConfiguration(task.getId())).isEqualTo(original);
    }

    @Test
    void changedOrUnavailableProviderCannotResumeEvenWhenModelAliasIsUnchanged() {
        ResearchTask task = task(1);
        service.verifyOrFreeze(task.getId(), "owner-a");
        String original = repository.readModelConfiguration(task.getId());
        when(configuration.chatProviderSnapshot()).thenReturn(Map.of("scope", "API_CONSTRUCTION", "host", "provider-b"));
        assertRejected(task, "owner-a", "不一致");
        when(configuration.chatProviderSnapshot()).thenThrow(new IllegalStateException("Provider identity not captured"));
        assertRejected(task, "owner-a", "清单");
        assertThat(repository.readModelConfiguration(task.getId())).isEqualTo(original);
    }

    @Test
    void changingPriceCardCannotRewriteAnExistingRunsAttribution() {
        ResearchTask task = task(1);
        service.verifyOrFreeze(task.getId(), "owner-a");
        String original = repository.readModelConfiguration(task.getId());
        when(pricing.snapshot()).thenReturn(Map.of("status", "CONFIGURED", "version", "new-card"));
        assertRejected(task, "owner-a", "不一致");
        assertThat(repository.readModelConfiguration(task.getId())).isEqualTo(original);
    }

    @Test
    void catalogOrderDoesNotChangeIdentityButPolicyOrRetrievalChangesDo() {
        var first = new CapabilityDescriptor("a", CapabilityDescriptor.ProviderType.LOCAL,
                "local", "readA", CapabilityDescriptor.RiskLevel.READ_ONLY, 1000, 1024, true);
        var second = new CapabilityDescriptor("b", CapabilityDescriptor.ProviderType.LOCAL,
                "local", "readB", CapabilityDescriptor.RiskLevel.READ_ONLY, 1000, 1024, true);
        when(capabilities.descriptors()).thenReturn(List.of(second, first));
        ResearchTask task = task(1);
        service.verifyOrFreeze(task.getId(), "owner-a");
        String original = repository.readModelConfiguration(task.getId());
        when(capabilities.descriptors()).thenReturn(List.of(first, second));
        service.verifyOrFreeze(task.getId(), "owner-a");
        when(rag.runtimeConfiguration()).thenReturn(Map.of("topK", 8));
        assertRejected(task, "owner-a", "不一致");
        when(rag.runtimeConfiguration()).thenReturn(Map.of("topK", 6));
        when(capabilities.descriptors()).thenReturn(List.of(first));
        assertRejected(task, "owner-a", "不一致");
        when(capabilities.descriptors()).thenReturn(List.of(first, second));
        when(replan.runtimeConfiguration()).thenReturn(Map.of("enabled", true));
        assertRejected(task, "owner-a", "不一致");
        when(replan.runtimeConfiguration()).thenReturn(Map.of("enabled", false));
        when(edgar.runtimeConfiguration()).thenReturn(Map.of("scope", "CURRENT_INGESTION_DEFAULTS", "childSize", 400));
        assertRejected(task, "owner-a", "不一致");
        assertThat(repository.readModelConfiguration(task.getId())).isEqualTo(original);
    }

    @Test
    void changedOrUnknownCodeCannotResumeAndKeepsOriginalManifest() {
        ResearchTask task = task(1);
        service.verifyOrFreeze(task.getId(), "owner-a");
        String original = repository.readModelConfiguration(task.getId());
        when(artifact.snapshot()).thenReturn(Map.of("status", "KNOWN", "sha256", "artifact-b"));
        assertRejected(task, "owner-a", "不一致");
        when(artifact.snapshot()).thenReturn(Map.of("status", "UNKNOWN"));
        assertRejected(task, "owner-a", "制品身份无法确认");
        assertThat(repository.readModelConfiguration(task.getId())).isEqualTo(original);
    }

    @Test
    void legacyRetryCannotInventTheConfigurationOfEarlierAttempts() {
        ResearchTask task = task(2);
        assertRejected(task, "owner-a", "历史尝试缺少");
        assertThat(repository.readModelConfiguration(task.getId())).isNull();
    }

    @Test
    void staleOwnerAndTerminalTaskCannotFreeze() {
        ResearchTask task = task(1);
        assertRejected(task, "owner-old", "不再持有");
        task.setStatus(ResearchTask.Status.SUCCEEDED);
        repository.saveAndFlush(task);
        assertRejected(task, "owner-a", "不再持有");
        assertThat(repository.readModelConfiguration(task.getId())).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{broken", "null", "[]", "{}", "{\"schemaVersion\":2}", "{\"schemaVersion\":4}", "{\"schemaVersion\":5}",
            "{\"schemaVersion\":1.0}", "{\"schemaVersion\":4294967297}",
            "{\"schemaVersion\":1} {}"})
    void malformedOrUnknownManifestIsPreserved(String stored) {
        ResearchTask task = task(1);
        jdbc.update("UPDATE research_tasks SET model_configuration_json = ? WHERE id = ?", stored, task.getId());
        assertRejected(task, "owner-a", "清单");
        assertThat(repository.readModelConfiguration(task.getId())).isEqualTo(stored);
    }

    private void assertRejected(ResearchTask task, String owner, String reason) {
        assertThatThrownBy(() -> service.verifyOrFreeze(task.getId(), owner))
                .isInstanceOf(ResearchRunManifestService.ManifestException.class)
                .hasMessageContaining(reason).hasMessageContaining("原清单与检查点已保留");
    }

    private ResearchTask task(int attempts) {
        ResearchTask task = new ResearchTask();
        task.setUserId("u_001");
        task.setIdempotencyKey("manifest:" + UUID.randomUUID());
        task.setTicker("AAPL");
        task.setStatus(ResearchTask.Status.RUNNING);
        task.setAttempts(attempts);
        task.setLeaseToken("owner-a");
        return repository.saveAndFlush(task);
    }

    @TestConfiguration
    static class Config {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean AgentRuntimeConfiguration configuration() { return mock(AgentRuntimeConfiguration.class); }
        @Bean CapabilityRegistry capabilities() { return mock(CapabilityRegistry.class); }
        @Bean RagService rag() { return mock(RagService.class); }
        @Bean DeepEvidenceReplanService replan() { return mock(DeepEvidenceReplanService.class); }
        @Bean EdgarIngestionService edgar() { return mock(EdgarIngestionService.class); }
        @Bean RuntimeArtifactIdentity artifact() { return mock(RuntimeArtifactIdentity.class); }
        @Bean ModelPricingProperties pricing() { return mock(ModelPricingProperties.class); }
    }
}
