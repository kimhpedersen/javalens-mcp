package org.javalens.mcp.fixtures;

import org.javalens.core.JdtServiceImpl;

import java.util.Collections;
import java.util.List;

/**
 * Result of loading a test fixture project.
 * Duplicate of {@code org.javalens.core.fixtures.LoadedFixture} — kept in sync.
 */
public record LoadedFixture(
    JdtServiceImpl service,
    ClasspathSnapshot classpath,
    List<String> warnings
) {
    public LoadedFixture {
        warnings = warnings == null ? Collections.emptyList() : Collections.unmodifiableList(warnings);
    }
}
