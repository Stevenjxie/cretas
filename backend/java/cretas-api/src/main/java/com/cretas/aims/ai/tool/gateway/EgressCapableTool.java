package com.cretas.aims.ai.tool.gateway;

import java.util.Set;

/**
 * Explicit marker for a Tool implementation that can perform governed network egress.
 *
 * <p>Implementations must return the same immutable, non-empty set of stable destination IDs for
 * their entire runtime lifetime. The IDs are policy identifiers, not caller-controlled URLs.</p>
 */
public interface EgressCapableTool {

    Set<String> getEgressDestinationIds();
}
