package org.javalens.core.fixtures;

import org.javalens.core.JdtServiceImpl;

import java.util.Collections;
import java.util.List;

/**
 * Result of loading a test fixture project.
 *
 * <p>Bundles the live {@link JdtServiceImpl} together with a {@link ClasspathSnapshot} captured
 * immediately after load and a list of any warnings surfaced during the load. The warnings list
 * is empty until PR-4 wires {@code LoadWarning} through {@code JdtServiceImpl.loadProject}.
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
