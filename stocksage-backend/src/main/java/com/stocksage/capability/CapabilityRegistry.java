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

/**
 * 启动时加载能力清单并绑定具体适配器的只读注册表。
 *
 * <p>清单来自仓库内 {@code capabilities/*.yml}，适配器来自 Spring Bean。两侧必须一一对应；
 * 重复、缺失或没有本地策略描述的适配器都会阻止启动。下游 Gateway 只从该不可变快照取能力。</p>
 */
@Component
public class CapabilityRegistry {

    /** Spring classpath 中版本库受控能力清单的位置。 */
    private static final String CATALOG_PATTERN = "classpath*:capabilities/*.yml";

    /** 按稳定能力 ID 索引的不可变“策略 + 适配器”快照。 */
    private final Map<String, RegisteredCapability> capabilities;

    /**
     * 校验并组装所有清单与适配器；发现不一致时失败启动。
     *
     * @param adapters Spring 扫描到的本地/MCP 能力适配器
     */
    public CapabilityRegistry(List<CapabilityAdapter> adapters) {
        Map<String, CapabilityAdapter> adapterById = new LinkedHashMap<>();
        for (CapabilityAdapter adapter : adapters) {
            if (adapterById.putIfAbsent(adapter.capabilityId(), adapter) != null) {
                throw new IllegalStateException("Duplicate capability adapter: " + adapter.capabilityId());
            }
        }

        Map<String, RegisteredCapability> loaded = new LinkedHashMap<>();
        // 清单是授权来源；只有与清单逐项匹配的适配器才能进入运行时注册表。
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

    /**
     * 按 ID 获取已绑定能力。
     *
     * @throws CapabilityException ID 不在本地清单中
     */
    public RegisteredCapability require(String capabilityId) {
        return Optional.ofNullable(capabilities.get(capabilityId))
                .orElseThrow(() -> new CapabilityException(CapabilityException.Reason.UNKNOWN,
                        "Unknown capability: " + capabilityId));
    }

    /** @return 所有本地能力描述的只读列表，供管理面板展示 */
    public List<CapabilityDescriptor> descriptors() {
        return capabilities.values().stream().map(RegisteredCapability::descriptor).toList();
    }

    /** 从所有 classpath YAML 清单读取并去重能力描述。 */
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

    /** YAML 顶层结构，仅用于反序列化。 */
    private record CapabilityCatalog(List<CapabilityDescriptor> capabilities) {
    }

    /** 已通过启动校验的一对本地策略描述和执行适配器。 */
    public record RegisteredCapability(CapabilityDescriptor descriptor, CapabilityAdapter adapter) {
    }
}
