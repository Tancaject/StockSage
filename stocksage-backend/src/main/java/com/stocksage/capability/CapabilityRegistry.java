package com.stocksage.capability;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Loads the version-controlled capability catalog and binds it to concrete adapters. */
@Component
public class CapabilityRegistry {

    private static final String CATALOG_PATTERN = "classpath*:capabilities/*.yml";

    private final Map<String, RegisteredCapability> capabilities;

    public CapabilityRegistry(List<CapabilityAdapter> adapters) {
        Map<String, CapabilityAdapter> adapterById = new LinkedHashMap<>();
        for (CapabilityAdapter adapter : adapters) {
            if (adapterById.putIfAbsent(adapter.capabilityId(), adapter) != null) {
                throw new IllegalStateException("Duplicate capability adapter: " + adapter.capabilityId());
            }
        }

        Map<String, RegisteredCapability> loaded = new LinkedHashMap<>();
        for (CapabilityDescriptor descriptor : loadDescriptors()) {
            CapabilityAdapter adapter = adapterById.remove(descriptor.id());
            if (adapter == null) {
                throw new IllegalStateException("No adapter registered for capability: " + descriptor.id());
            }
            if (loaded.putIfAbsent(descriptor.id(), new RegisteredCapability(descriptor, adapter)) != null) {
                throw new IllegalStateException("Duplicate capability descriptor: " + descriptor.id());
            }
        }
        if (!adapterById.isEmpty()) {
            throw new IllegalStateException("Capability adapter has no local policy descriptor: "
                    + adapterById.keySet());
        }
        this.capabilities = Map.copyOf(loaded);
    }

    public RegisteredCapability require(String capabilityId) {
        return Optional.ofNullable(capabilities.get(capabilityId))
                .orElseThrow(() -> new CapabilityException(CapabilityException.Reason.UNKNOWN,
                        "Unknown capability: " + capabilityId));
    }

    public List<CapabilityDescriptor> descriptors() {
        return capabilities.values().stream().map(RegisteredCapability::descriptor).toList();
    }

    private List<CapabilityDescriptor> loadDescriptors() {
        YAMLMapper mapper = YAMLMapper.builder()
                .findAndAddModules()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            Resource[] resources = resolver.getResources(CATALOG_PATTERN);
            if (resources.length == 0) {
                throw new IllegalStateException("No capability catalogs found at " + CATALOG_PATTERN);
            }
            Map<String, CapabilityDescriptor> descriptors = new LinkedHashMap<>();
            for (Resource resource : resources) {
                try (var input = resource.getInputStream()) {
                    CapabilityCatalog catalog = mapper.readValue(input, CapabilityCatalog.class);
                    if (catalog.capabilities() == null) {
                        continue;
                    }
                    for (CapabilityDescriptor descriptor : catalog.capabilities()) {
                        if (descriptors.putIfAbsent(descriptor.id(), descriptor) != null) {
                            throw new IllegalStateException("Duplicate capability descriptor: " + descriptor.id());
                        }
                    }
                }
            }
            return List.copyOf(descriptors.values());
        } catch (IOException error) {
            throw new IllegalStateException("Failed to load capability catalogs", error);
        }
    }

    private record CapabilityCatalog(List<CapabilityDescriptor> capabilities) {
    }

    public record RegisteredCapability(CapabilityDescriptor descriptor, CapabilityAdapter adapter) {
    }
}
