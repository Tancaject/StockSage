package com.stocksage.skill;

import com.stocksage.capability.CapabilityAdapter;
import com.stocksage.capability.CapabilityInvocationContext;
import com.stocksage.capability.CapabilityRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SkillRegistryTest {

    @Test
    void loadsAndValidatesTheRepositoryOwnedNewsSkills() {
        CapabilityRegistry capabilities = new CapabilityRegistry(List.of(
                adapter("local.news.searchNews"),
                adapter("mcp.news.search")
        ));
        SkillRegistry registry = new SkillRegistry(new SkillValidator(capabilities));

        assertThat(registry.list()).extracting(SkillDefinition::id)
                .containsExactlyInAnyOrder("latest-news-mcp", "local-latest-news");
        assertThat(registry.find("latest-news-mcp")).get()
                .extracting(SkillDefinition::enabled)
                .isEqualTo(true);
    }

    private CapabilityAdapter adapter(String id) {
        return new CapabilityAdapter() {
            @Override
            public String capabilityId() {
                return id;
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String invoke(Map<String, Object> arguments, CapabilityInvocationContext context) {
                return "";
            }
        };
    }
}
