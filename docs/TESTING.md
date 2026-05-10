# Testing JavaLens Tools

This document is the contract for tests in `org.javalens.mcp.tests/src/org/javalens/mcp/tools/`. It exists because we shipped 1.3.0 with shape-only tool tests and immediately surfaced a class of silent-incompleteness bugs that the tests should have caught.

## The Completeness Principle

`success: true` from any MCP tool must mean: **the result is complete and correct for the given input**.

There are exactly two acceptable outcomes for any tool call:

1. **Success with complete result** — `success: true` and the response contains the full correct answer.
2. **Explicit signal of incompleteness or failure** — `success: false` with a reason, OR `success: true` with a `complete: false` flag and a reason field describing what is missing or degraded.

There is no acceptable third outcome. Returning a plausibly-shaped response with silently incomplete or wrong content is the worst failure mode for an AI agent: the agent has no signal to compensate, so it acts confidently on bad data. Every tool must avoid that mode by construction.

When a tool's underlying JDT call can return partial results (e.g., binding resolution returned null, type hierarchy was incomplete, the search index wasn't fully built), the tool must either fail outright with a reason or include the incompleteness signal in its successful response. Silent fallbacks that produce empty/partial answers without signaling are bugs.

## Deterministic Test Matrix

Tools are deterministic. Given a frozen fixture, a fixed JDT version, and a specific input position, every tool returns a fixed answer. Tests assert that exact answer.

For every tool, the test matrix is derived from three sources:

1. **The tool's `getDescription()`** — every claimed capability becomes at least one positive test pattern. If the description says "find all implementations including transitive", a test must assert that for a fixture with a known transitive chain, all transitive members appear.
2. **The tool's `getInputSchema()`** — every documented parameter and option becomes a test pattern. Optional params, max-result limits, and filter parameters each need their own coverage.
3. **The JDT API the tool calls** — each JDT API has quirks (e.g., `IJavaSearchConstants.IMPLEMENTORS` returns only direct implementors; `IBinding.resolveBinding()` can return null in certain configurations). For each quirk relevant to the tool, a test must exercise that quirk against a fixture that triggers it.

In addition, every tool covers at least:

- **One positive case per claimed capability** — the tool returns the right answer.
- **One negative case per ambiguity-prone scenario** — the tool does NOT return false positives (e.g., `find_implementations(X)` does not include subtypes of unrelated type Y, even if Y has the same simple name).
- **One isolation/edge case** — empty result for a target with no matches; cross-file or cross-module scope where applicable; behavior on input the tool's description doesn't claim to handle (should be a clear error, not silent garbage).

## What "Semantic-Grade Test" Means

A semantic-grade test asserts the **exact expected content** of the tool response, not just response shape.

❌ Shape-only (do not write tests like this):

```java
assertTrue(response.isSuccess());
assertNotNull(data.get("implementations"));
List<Map<String, Object>> impls = (List<Map<String, Object>>) data.get("implementations");
for (Map<String, Object> impl : impls) {
    assertNotNull(impl.get("qualifiedName"));
}
```

This passes for an empty list, a list with only direct implementors, a list with one wrong entry, etc. It tells us nothing about correctness.

✅ Semantic-grade (write tests like this):

```java
assertTrue(response.isSuccess());
assertEquals(3, data.get("totalImplementations"));

Set<String> qualifiedNames = ((List<Map<String, Object>>) data.get("implementations")).stream()
    .map(m -> (String) m.get("qualifiedName"))
    .collect(Collectors.toSet());

assertEquals(
    Set.of("com.example.IFillable", "com.example.Rectangle", "com.example.FilledCircle"),
    qualifiedNames,
    "find_implementations(IShape) must return exactly the transitive sub-interface and class implementors"
);

// Isolation: unrelated hierarchy MUST NOT appear
assertFalse(qualifiedNames.contains("com.example.Animal"));
assertFalse(qualifiedNames.contains("com.example.Dog"));
```

The semantic test states what the tool should do, and asserts the tool does exactly that.

## The `@Disabled("Pending: …")` Convention

When a test is written to capture the contract but the tool currently fails to meet it, mark the test:

```java
@Test
@Disabled("Pending: tool uses IMPLEMENTORS search which only finds direct implementors; transitive sub-interface chain implementors are silently absent")
@DisplayName("find_implementations on root interface returns all transitive implementors")
void findImplementations_transitiveSubInterfaceChain() {
    // ... full semantic assertion as if the tool worked correctly ...
}
```

The test exists in code as the contract specification. CI skips it (so the build stays green), but it is visible, runnable on demand, and surfaces in test reports as skipped with the reason inline.

When the tool is fixed, the `@Disabled` marker is removed and the test runs as part of the suite.

**Release gate**: zero `@Disabled("Pending:` markers in `org.javalens.mcp.tests/`. Run this check before tagging:

```bash
grep -r '@Disabled("Pending' org.javalens.mcp.tests/src/ | wc -l
# Must output: 0
```

Use the `Pending:` prefix specifically (not just `@Disabled`) so the gate-check command can distinguish "test pending a tool fix" from any future use of `@Disabled` for unrelated reasons.

## Reusable Test Infrastructure

Common helpers live in `org.javalens.mcp.tests/src/org/javalens/mcp/fixtures/`:

- `TestProjectHelper` — loads fixtures (`loadProject(name)`, `loadFixture(name)`, `getFixturePath(name)`).
- `ClasspathSnapshot` — captured classpath state for `load_project`-level assertions.
- `SemanticAssertions` (added in 1.3.1) — common exact-content matchers for tool responses (qualified-name set assertions, edit-list shape assertions, diagnostic list assertions, etc.).

When a test pattern recurs across tools, the assertion logic should be lifted into `SemanticAssertions` rather than duplicated.

## Fixtures

Tests run against deterministic project fixtures under `org.javalens.core.tests/test-resources/sample-projects/`. The tool-level test surface uses `simple-maven` (extended with the patterns listed below) for most cases and `multi-module-maven` for cross-module scenarios.

The principle for fixtures: **every Java pattern a tool must handle has explicit, named, unambiguous content in a fixture, and tests assert against that content by name and position**. No reliance on JDK types, no fixtures-of-convenience.

For the full pattern catalog and per-tool checklist process, see the 1.3.1 plan (`working/PLAN.md` or the upstream plan reference).
