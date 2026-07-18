package com.stocksage.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpCapabilityProviderTest {

    @Mock
    private ObjectProvider<List<McpSyncClient>> clients;
    @Mock
    private McpSyncClient client;

    private McpProperties properties;
    private McpCapabilityProvider provider;

    @BeforeEach
    void setUp() {
        properties = new McpProperties();
        properties.setEnabled(true);
        properties.setAllowedTools(Set.of("test-news/search_news"));
        properties.setNewsSearchServer("test-news");
        properties.setNewsSearchTool("search_news");
        provider = new McpCapabilityProvider(
                clients,
                properties,
                new StockSageMcpToolFilter(properties),
                new ObjectMapper()
        );
    }

    @Test
    void initializesListsAndCallsOnlyTheApprovedTool() {
        McpSchema.Implementation server = new McpSchema.Implementation("test-news", "1.0.0");
        McpSchema.InitializeResult initialized = new McpSchema.InitializeResult(
                "2025-06-18", null, server, "");
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("search_news")
                .description("read-only news search")
                .inputSchema(new McpSchema.JsonSchema(
                        "object", Map.of(), List.of(), false, null, null))
                .build();

        when(clients.getIfAvailable(any())).thenReturn(List.of(client));
        when(client.isInitialized()).thenReturn(false);
        when(client.initialize()).thenReturn(initialized);
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(tool), null));
        when(client.callTool(any())).thenReturn(new McpSchema.CallToolResult("remote-ok", false));

        assertThat(provider.isNewsSearchAvailable()).isTrue();
        String result = provider.callNewsSearch(Map.of("query", "NVDA", "maxResults", 5));

        assertThat(result).contains("remote-ok");
        verify(client).initialize();
        verify(client).listTools();
        verify(client).callTool(argThat(request ->
                request.name().equals("search_news")
                        && request.arguments().get("query").equals("NVDA")));
    }

    @Test
    void rejectsAnAllowlistedToolWhoseNameSignalsSideEffects() {
        properties.setAllowedTools(Set.of("test-news/placeOrder"));
        properties.setNewsSearchTool("placeOrder");
        provider = new McpCapabilityProvider(
                clients,
                properties,
                new StockSageMcpToolFilter(properties),
                new ObjectMapper()
        );
        McpSchema.InitializeResult initialized = new McpSchema.InitializeResult(
                "2025-06-18", null,
                new McpSchema.Implementation("test-news", "1.0.0"), "");
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("placeOrder")
                .description("write")
                .inputSchema(new McpSchema.JsonSchema(
                        "object", Map.of(), List.of(), false, null, null))
                .build();
        when(clients.getIfAvailable(any())).thenReturn(List.of(client));
        when(client.isInitialized()).thenReturn(false);
        when(client.initialize()).thenReturn(initialized);
        when(client.listTools()).thenReturn(new McpSchema.ListToolsResult(List.of(tool), null));

        assertThat(provider.isNewsSearchAvailable()).isFalse();
        assertThat(provider.status().approvedToolCount()).isZero();
    }
}
