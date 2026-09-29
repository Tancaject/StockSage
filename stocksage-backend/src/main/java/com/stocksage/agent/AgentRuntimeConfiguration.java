package com.stocksage.agent;

import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Captures the defaults used to construct clients, not provider revisions or response usage. */
@Component
public class AgentRuntimeConfiguration {

    private static final Set<String> ROLES = Set.of(
            "fundamentals", "market", "news", "bull", "bear", "manager", "scoring", "continuation", "replan",
            "query-rewrite", "contextual-gist");
    private final Map<String, Object> clients = new TreeMap<>();
    private Map<String, Object> chatProvider;

    public synchronized void recordChatProvider(String baseUrl, String completionsPath) {
        if (chatProvider != null) throw new IllegalStateException("Chat provider already registered");
        chatProvider = Map.of("scope", "API_CONSTRUCTION", "protocol", "OPENAI_COMPATIBLE",
                "base", endpointIdentity(baseUrl), "completions", endpointIdentity(completionsPath),
                "excluded", "USERINFO_QUERY_FRAGMENT_HEADERS_CREDENTIALS",
                "unknownProviderRevision", true);
    }

    public synchronized Map<String, Object> chatProviderSnapshot() {
        if (chatProvider == null) throw new IllegalStateException("Chat provider has not been registered");
        return chatProvider;
    }

    public static Map<String, Object> endpointIdentity(String value) {
        try {
            var uri = new java.net.URI(value);
            // Paths may contain tenant identifiers; only their fingerprint leaves the construction boundary.
            return Map.of("scheme", uri.getScheme() == null ? "" : uri.getScheme(),
                    "host", uri.getHost() == null ? "" : uri.getHost(), "port", uri.getPort(),
                    "pathSha256", sha256(uri.getRawPath() == null ? "" : uri.getRawPath()));
        } catch (java.net.URISyntaxException | NullPointerException invalid) {
            // URI parser exceptions contain the original input, which can contain credentials.
            throw new IllegalArgumentException("Provider endpoint identity cannot be parsed");
        }
    }

    public synchronized void recordClientDefaults(String role, OpenAiChatOptions options, String systemPrompt) {
        if (!ROLES.contains(role) || clients.containsKey(role)) {
            throw new IllegalStateException("Unknown or already registered agent role: " + role);
        }
        Map<String, Object> defaults = new TreeMap<>();
        defaults.put("scope", "CLIENT_DEFAULTS");
        defaults.put("model", options.getModel());
        defaults.put("temperature", options.getTemperature());
        defaults.put("maxTokens", options.getMaxTokens());
        defaults.put("systemPromptHash", sha256(systemPrompt));
        defaults.put("unknownProviderRevision", true);
        clients.put(role, Collections.unmodifiableMap(defaults));
    }

    public synchronized Map<String, Object> snapshot() {
        Set<String> missing = new TreeSet<>(ROLES);
        missing.removeAll(clients.keySet());
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Agent client configuration is incomplete; missing roles: " + missing);
        }
        return Collections.unmodifiableMap(new TreeMap<>(clients));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM does not support SHA-256", impossible);
        }
    }
}
