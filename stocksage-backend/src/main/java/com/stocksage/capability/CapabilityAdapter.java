package com.stocksage.capability;

import java.util.Map;

/** Provider-specific execution adapter registered under a stable local capability id. */
public interface CapabilityAdapter {

    String capabilityId();

    boolean isAvailable();

    String invoke(Map<String, Object> arguments, CapabilityInvocationContext context) throws Exception;
}
