package com.att.device.management.adapter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry & Router for device protocol adapters.
 * Enables zero-downtime registration of new protocols and dynamic routing based on device capabilities.
 */
@Component
public class ProtocolAdapterRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProtocolAdapterRegistry.class);
    private final Map<String, DeviceProtocolAdapter> adapters = new ConcurrentHashMap<>();

    public ProtocolAdapterRegistry(List<DeviceProtocolAdapter> adapterList) {
        for (DeviceProtocolAdapter adapter : adapterList) {
            adapters.put(adapter.getProtocolName().toUpperCase(), adapter);
            log.info("Registered Protocol Adapter: {}", adapter.getProtocolName());
        }
    }

    public DeviceProtocolAdapter getAdapter(String protocolName) {
        DeviceProtocolAdapter adapter = adapters.get(protocolName.toUpperCase());
        if (adapter == null) {
            // Default fallback to REST_NUDGE
            return adapters.getOrDefault("REST_NUDGE", null);
        }
        return adapter;
    }
}

