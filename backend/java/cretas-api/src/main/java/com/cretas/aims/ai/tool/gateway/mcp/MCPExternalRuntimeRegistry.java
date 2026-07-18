package com.cretas.aims.ai.tool.gateway.mcp;

import com.cretas.aims.mcp.MCPToolProxy;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Isolated holding registry for validated outbound capabilities.
 *
 * <p>It deliberately exposes no executor lookup or execute method. Until the governed gateway is
 * implemented, existing ToolRegistry/Map-context callers therefore have zero executable outbound
 * MCP capabilities.</p>
 */
@Component
public final class MCPExternalRuntimeRegistry {

    private final Map<String, MCPToolProxy> capabilities = new ConcurrentHashMap<>();

    public boolean registerValidated(MCPToolProxy proxy) {
        if (proxy == null) {
            throw new IllegalArgumentException("proxy must not be null");
        }
        return capabilities.putIfAbsent(proxy.getToolName(), proxy) == null;
    }

    public boolean hasCapability(String localToolName) {
        return capabilities.containsKey(localToolName);
    }

    public Set<String> capabilityNames() {
        return Set.copyOf(capabilities.keySet());
    }

    public int size() {
        return capabilities.size();
    }
}
