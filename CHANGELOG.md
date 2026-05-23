# Changelog

## 1.3.3 - 2026-05-22

Audit blind-spot remediation. 1.3.2 shipped with a "is every branch tested?" audit; three post-release bug reports (#13, #15-secondary, #17) showed the wrong question was being asked. This release closes the underlying categories: AST-visitor enumeration gaps, type-system fixture matrix coverage, adversarial-input handling, JDT-quirk pinning, scale validation, concurrency invariants, build-system matrix, cross-tool workflow guards, and tool-contract parity.

### Closes reported issues

- **Issue #17** — `find_unused_code` reported every private field of a generic class as unused even when read in the same class. Root cause: JDT's `IBinding.equals()` returns false between the declaration-context binding and the parameterized-member-access-context binding for the same field. Fix: canonicalize every binding via `getVariableDeclaration()` / `getMethodDeclaration()` before set membership checks.
- **Issue #13** — `rename_symbol` returned 0 edits on an 11k-file project despite many real references. Root cause: the AST-binding-key path parsed every Java file with full binding resolution; under JDT index pressure at scale, bindings intermittently resolved to null and were silently skipped. Fix: dispatch `IMember` targets through `SearchService.findAllReferences` (index-driven, resilient at scale). `ILocalVariable` and `ITypeParameter` stay on the AST path with declaring-CU-only scope. Same scenario at 1000-class fixture scale now runs 3.5x faster.
- **Issue #15-secondary** — `change_method_signature` was reported to silently drop `this(args)` / `super(args)` constructor delegation sites. Investigation confirmed the source already handled both; regression guards now pin the contract.

### Tool fixes — call-site / reference-search completeness (Category A)

- **`change_method_signature`**: `super.method()` (`SuperMethodInvocation`) call sites are now propagated. Method references (`Foo::method`, `instance::method`, `super::method`, `Foo::new`) now surface in a `methodReferences` collector with `warnings` rather than being silently dropped — replacing the underlying method reference textually would either rebind to the new signature or require synthesizing a lambda, both of which depend on context the tool cannot recover.
- **`find_unused_code`**: canonicalized bindings (issue #17 fix above); regression guards for private methods referenced only via method reference.
- **`get_call_hierarchy_outgoing`**: now emits callees for all four `MethodReference` AST subtypes with `callType="METHOD_REFERENCE"`; previously only `MethodInvocation` and constructor invocations were surfaced.
- **`analyze_method`**: callees now include `this()` / `super()` delegations (`callType="THIS_CONSTRUCTOR"` / `"SUPER_CONSTRUCTOR"`) and the four method-reference subtypes.
- **`analyze_data_flow`**: qualified-field-access writes (`this.x = v`, `super.x = v`, `Outer.x = v`) are now correctly classified as writes (previously misclassified as reads). Compound assignment (`x += 5`) is now counted as both a read and a write.
- **`inline_method`**: refuses outright when the inlined body contains `super.method()`, `super.field`, `super::method`, or `super()` — the binding would silently rebind to a different super-hierarchy after inlining.
- **`extract_constant`**: refuses extraction when the expression references instance state (`this`, instance field, non-static method). Previously emitted uncompilable `private static final` declarations referencing instance members.
- **`extract_variable`**: refuses extraction when the lift would change evaluation semantics — for-loop condition, while/do condition, short-circuit `&&`/`||` RHS, ternary then/else branches.
- **`extract_interface`**: propagates both class-level (`<T extends Number>`) and method-level (`<U>`) type parameters to the interface declaration and methods, plus the `implements` clause's type arguments.
- **`extract_method`**: redeclares the variable at the call site when the extracted block declared a returned variable. Adds the containing method's type parameters to the new method when used.
- **`convert_anonymous_to_lambda`**: completes the refusal-decision matrix — refuses on `super` references in the body, fields/initializers/nested types in the anonymous class body. Correctly does NOT refuse on `EnclosingClass.this` (resolves identically in both contexts).
- **`find_circular_dependencies`** and **`get_dependency_graph`**: correctly resolve the package for all import forms — regular, on-demand, static (`import static com.foo.Bar.method`), static-on-demand, nested-class imports. Previously only the simple `com.foo.Bar` form resolved.
- **`find_large_classes`**: scans all top-level type kinds (class, interface, enum, record, annotation). Previously skipped enums, records, and annotation types.
- **`find_naming_violations`**: covers `record` and `annotation` declarations (in addition to class/interface/enum).
- **`find_possible_bugs`**: implements the null-pointer-deref detector documented in `getDescription` but previously absent — conservative detection for locals whose only assignment is a `NullLiteral` initializer.
- **`find_tests`**: recognizes JUnit 5's `@TestFactory` and `@TestTemplate` annotations (in addition to `@Test`, `@ParameterizedTest`, `@RepeatedTest`).
- **`get_document_symbols`**: emits record components as child symbols (`kind="recordComponent"`). JDT 3.39's `IType.getFields()` does not include them and `IRecordComponent` is not exposed publicly; the fix parses the AST `RecordDeclaration.recordComponents()`.
- **`get_hover_info`** and **`get_signature_help`**: include type parameters in type and method signatures.
- **`get_javadoc`**: parses JEP 285 standard tags (`@apiNote`, `@implSpec`, `@implNote`) and merges `@exception` into the `throws` list as a synonym.
- **`inline_variable`**: regression guard for for-init declarations being correctly refused (modification-via-update detected by the existing visitor).

### Tool fixes — type-system dimension coverage (Category B1)

- **`analyze_type`**: emits `typeParameters` and `typeParameterBounds`; previously omitted both.
- **`analyze_method`**: emits method-level `typeParameters` and `typeParameterBounds`; previously omitted both.
- **`rename_symbol`**: now handles `ITypeParameter` rename targets; previously the source range was unrecognized and rename produced a silent no-op.
- **`ModifierFormatter`** (shared by ~10 tools): emits `sealed` and `non-sealed` modifiers; previously absent, so `analyze_file` etc. reported a sealed interface's modifiers as `[public]`.
- **`extract_method`**: copies the containing method's type parameters to the extracted method when used in the lifted body.

### Service-layer fixes (Category B2 — propagate to every position-resolving tool)

- **`getElementAtPosition`** refuses positions inside string literals (`"..."`), character literals, text blocks, line comments (`// ...`), block comments (`/* ... */`), and Javadoc text. Previously, `codeSelect` returning empty would fall back to `getElementAt(offset)` which returned the enclosing method as a "found" symbol — silent misleading success. Additionally, `codeSelect` itself tokenizes comment text and matched words like `"Empty"` inside `// Empty catch block` against `sun.invoke.empty.Empty`; the literal/comment check now runs BEFORE `codeSelect`.
- Position past EOF now returns a clean failure.

### JDT contract pinning (Category B3)

`JdtContractTest` now pins:
- `IBinding` equality for parameterized members (declaration-context binding ≠ usage-context binding; `getXxxDeclaration()` returns the canonical form for both — the issue #17 quirk).
- Type-variable bindings report `isTypeVariable=true`, `isClass=false`, `isInterface=false`.
- `MethodInvocation.resolveMethodBinding()` for overloaded methods selects the matching overload (`foo(42)` resolves to `foo(int)`, not `foo(String)`).
- SearchEngine matches against JDK types have non-null `getElement()`.
- `codeSelect` at the `package` keyword returns either empty or `IPackageDeclaration` / `IPackageFragment` — never an unexpected type.
- `IType.findType(String)` accepts dotted nested-type form (`java.util.Map.Entry`); does NOT accept `$`-form (`java.util.Map$Entry`).

### Scale validation (Category C1)

- New `ScaleFixtureGenerator` produces a JVM-cached 1000-leaf-class Maven fixture: 100 packages × 10 leaves + 1 hub class declaring `public int counter` (referenced by every leaf) + 1 `Marker` interface (implemented by every leaf).
- New `ScaleToolsTest` exercises `rename_symbol`, `find_references`, `find_implementations`, `search_symbols`, and `find_unused_code` against the scale fixture with exact-count assertions (not "non-null"). `rename_symbol` on `Hub.counter` produces 2001 edits in ~1.7 seconds via the new SearchEngine path.

### Concurrency / lifecycle (Category C2)

- `AbstractTool.execute` LOADING and FAILED dispatch branches are pinned deterministically.
- `JavaLensApplication.stop()` is idempotent; the `running` flag is volatile and per-instance; cross-thread visibility of the stop signal is exercised.
- Per-session state fields (`running`, `loadingState`, `jdtService`, `loadingError`) are structurally verified non-static so two in-process app instances do not corrupt each other's state.

### Build-system / environment matrix (Category C3)

- New `modular-maven` fixture: a project declaring `module-info.java` loads cleanly, member types resolve via `findType`, the declared Java 21 compliance is preserved.
- Existing `compliance-level-mismatch` fixture extended: source level higher than JDT supports (declared `99`) loads without crashing, `compilerSource` remains a parseable Java level.
- New `composite-gradle` fixture: a Gradle root declaring `includeBuild 'shared-lib'` loads, root sources enumerate via `getAllJavaFiles`, cross-build `findType` either resolves or returns null cleanly (never throws). Full cross-build symbol resolution requires Gradle execution, out of test-fragment scope.

### Adversarial input tolerance (Category B2)

- `AdversarialInputTest` pins UTF-8 BOM tolerance, CRLF line endings (positions resolve correctly), non-ASCII identifiers (`Café.java` with class `Café` and field `naïveCount` resolves via `findType`), and symbolic links (platform-conditional, skipped on Windows without Developer Mode).

### Cross-tool workflows (Category D1)

`CrossToolWorkflowTest` covers four scenarios end-to-end with apply-and-reload cycles:
- rename then find-references: reference cardinality preserved.
- organize-imports then validate-syntax: zero errors after apply.
- inline-method then get-diagnostics: no NEW errors above the baseline.
- change-method-signature then find-references: call sites preserved across signature changes.

### Description-vs-implementation parity (Category D2)

`ToolContractParityTest` iterates every registered tool and pins: lower_snake_case naming, `USAGE:` + `OUTPUT:` description convention, non-empty input schema, dispatch returns documented error codes (no thrown exceptions, no null), and per-tool valid invocation against simple-maven produces a well-formed response. Caught real drift: `analyze_file`, `analyze_method`, `analyze_type`, `get_type_usage_summary` now follow the `USAGE:` / `OUTPUT:` convention used by every other tool.

### Internal cleanup

- Removed `docs/TESTING.md` (stale — its core rule mandated `@Disabled("Pending:")` for unfixed contracts, which contradicts the current rule that failing tests are unfixed work).

## 1.3.2 - 2026-05-19

### Output shape — unified across reference-search tools

JavaLens output is consumed only by LLMs that re-read each tool's schema on call. The 1.3.x reference-search tools previously returned the same conceptual list under different field names per tool, forcing the consumer to remember three conventions. Renamed for consistency:

- `find_references` now returns `locations` (was `references`) and `totalCount` (was `totalReferences`).
- All fine-grain reference tools — `find_casts`, `find_instanceof_checks`, `find_type_instantiations`, `find_throws_declarations`, `find_catch_blocks`, `find_type_arguments`, `find_annotation_usages`, `find_method_references` — now return `locations` / `totalCount` / `advice` under those exact names. Previously each tool used a different result-list field (`casts`, `instanceofChecks`, `instantiations`, `throwsDeclarations`, `catchBlocks`, `typeArgumentUsages`, `usages`) and a different count field.
- `analyze_change_impact.affectedFiles[]` entries now use `filePath` (was `file`) to match every other tool's path field.

### Input parameter — unified for `find_*` tools

- `find_throws_declarations` and `find_catch_blocks` now accept `typeName` (was `exceptionType`).
- `find_annotation_usages` now accepts `typeName` (was `annotation`).

### Bug fixes

- **`change_method_signature` on constructors** no longer emits a syntactically-invalid `void Foo(...)` signature. The return-type prefix is now omitted for constructors, and the response's `oldReturnType` / `newReturnType` keys are omitted entirely (rather than reported as `"void"`).
- **`rename_symbol` of a method** now propagates the rename to overriding methods in subtypes and overridden methods in supertypes. Previously, renaming an interface method would edit only the declaration and leave implementors uncompilable.
- **`analyze_type` of an annotation type** now includes the `annotationUsages` counter alongside `instantiations`, `casts`, `instanceofChecks`, `typeArguments`. Missing in 1.3.1.
- **`get_type_members` nested `@interface`** now reports `kind: annotation`, matching the top-level case. Previously reported `Interface` because the nested-type branch checked `isInterface()` before `isAnnotation()`.

### Validation — strict `maxResults`

Six tools — `search_symbols`, `find_implementations`, `find_references`, `find_field_writes`, `get_call_hierarchy_incoming`, `get_document_symbols` — previously silently clamped `maxResults` to `[1, N]`, so a caller passing `0` got `1` result with no signal. All `maxResults`-accepting tools now reject negative values with `INVALID_PARAMETER` and honor `0` literally (return zero results). The `INVALID_PARAMETER` message names the offending parameter.

`search_symbols.offset` gets the same treatment: negative offset rejected explicitly.

### Type kind strings unified to lowercase

Every tool that reports a type kind now emits one of `class`, `interface`, `enum`, `record`, `annotation` (lowercase). 1.3.1 mixed capitalization across tools — `get_dependency_graph` emitted lowercase while others emitted `Class` / `Interface` / etc. The mixed shape made it harder for AI consumers to write robust filters.

### Response envelope on paginated tools

All 18 tools that accept `maxResults` now populate `meta.truncated`, `meta.totalCount`, and `meta.returnedCount` on every successful response. The four that were missing one or more of these fields — `search_symbols`, `suggest_imports`, `get_di_registrations`, `find_reflection_usage` — have been fixed. `truncated` is now driven by the actual pre-cap count compared to the returned-list size, so it is accurate when matches exactly equal `maxResults`.

### Suggest imports — filter internal packages

`suggest_imports` now omits candidates from packages matching `sun.*`, `com.sun.*`, `*.internal.*`, or `*.impl.*`. Previously the LLM could be handed implementation-detail types as if they were legitimate import targets.

### Test infrastructure

- 1.3.2 ships the source-side coverage audit across all 60+ MCP tools. Every documented branch now has at least one assertion that would fail if the branch broke, including absence-of-key contracts (when an optional field is documented to be omitted in a given case).
- New `JdtContractTest` pins the JDT-API behaviors JavaLens depends on (dotted-vs-$-form `findType`, `@interface` ordering quirk on `isAnnotation` / `isInterface`, search-match subclass per kind, `R_REGEXP_MATCH` unimplemented). A future JDT upgrade surfaces "JDT changed" cleanly vs "our wrapper broke".
- New `ResponseEnvelopeContractTest` and `MaxResultsBoundaryContractTest` enforce the envelope + maxResults contracts across every paginated tool — no per-tool drift.
- New `ErrorCodeContractTest` enforces the documented `INVALID_PARAMETER` failure path on every tool with required params.
- New fixtures (`broken-symbols`, `OrganizeImportsFixture`, `Java21Modern`, additions to `TypeKindsFixture`) cover problem-trigger paths, static/on-demand imports, modern Java syntax, and the 200-character Javadoc truncation contract.

### Internal refactors

These do not change behavior but reduce surface area for future regressions:

- `ProjectImporter` split from a 1484-LOC god class into a 416-LOC orchestrator plus `MavenImporter`, `GradleImporter`, `BazelImporter`, `LinkedFolderConfigurator`, and a `BuildSystemImporter` strategy interface. Adding a new build system is now an interface implementation plus a single map entry.
- `SearchService` collapses 8 named fine-grain methods into one `findReferences(IType, ReferenceKind, int)` entry point.
- 8 fine-grain `find_*` tools collapse onto a shared `AbstractFineGrainReferenceTool` base.
- `TypeKindResolver` and `ElementKindResolver` centralize kind-string emission so capitalization and ordering quirks are fixed in one place.
- `ModifierFormatter`, `MethodFormatter`, `MatchResolver`, `SchemaBuilder` consolidate previously-duplicated patterns.
- `ErrorInfo.fromThrowable` unwraps JDT `CoreException` into structured error info (plugin id, status code, severity) on every tool's catch-all.

## 1.3.1 - 2026-05-15

### Tool output fixes

Tools that previously returned wrong or empty answers in 1.3.0 now return correct answers. If your workflow relied on any of these, the output will change.

- **Annotation types** (`@interface`) are now classified as `Annotation` instead of `Interface`. Affects `analyze_type`, `analyze_file`, `find_implementations`, `get_hover_info`, `get_type_at_position`, `get_document_symbols`, `get_type_hierarchy`, `get_type_members`, `get_enclosing_element`, `get_symbol_info`, `go_to_definition`, `rename_symbol`, `search_symbols`, `get_type_usage_summary`, `get_dependency_graph`, `find_references` — every tool that reports a type kind.
- **Record types** now report `kind: Record` instead of `Class`.
- **`find_references.referenceKind`** classifies by the reference's actual role (`TYPE_REFERENCE` / `FIELD_ACCESS` / `METHOD_INVOCATION`) instead of by the enclosing element. A type usage inside a field declaration is no longer `FIELD_ACCESS`; a field read inside a method body is no longer `METHOD_INVOCATION`.
- **`search_symbols`** `?` single-char wildcard now works as documented. `?Shape` matches `IShape`. Previously returned empty.
- **`find_implementations`** returns transitive implementors (subinterface chains and multi-level class hierarchies), not just direct ones. Also now finds annotation-type users.
- **`extract_interface`** no longer pulls `Object` methods (`toString`/`hashCode`/`equals`) into the extracted interface by default.
- **`change_method_signature`** updates constructor call sites, not just method invocations.
- **`organize_imports`** correctly identifies unused imports. In 1.3.0 every import was reported as used because the import declaration itself was being counted as a reference to its own type.
- **`get_diagnostics`**, **`analyze_type`**, **`analyze_file`**, **`get_quick_fixes`** reliably surface compilation errors and warnings. These tools silently returned empty results in 1.3.0 for many files.
- **`get_quick_fixes`** offers "Remove unused import" fixes for files with unused imports.
- **`get_diagnostics`** `maxResults` cap is now actually applied (was a no-op).
- **`find_reflection_usage`** `maxResults` is now per reflection-API category as documented (was per method overload, returning more than asked).
- **`get_enclosing_element`** more reliably resolves the enclosing method at the requested position.

### Search engine

- Fine-grain reference searches — `find_annotation_usages`, `find_casts`, `find_instanceof_checks`, `find_throws_declarations`, `find_catch_blocks`, `find_type_arguments`, `find_type_instantiations` — now find references to **nested types** like `Outer.Inner` (previously returned nothing).
- Fine-grain searches for common JDK types no longer stall scanning the JDK index.
- Fine-grain search results no longer include JDK-internal entries with broken file paths.

## 1.3.0 - 2026-05-08

### Build system support — major overhaul

End-to-end coverage for Maven, Gradle, and Bazel now exercised in CI on Linux, macOS, and Windows against synthetic real-shaped fixtures (multi-module reactors with cross-module deps, real external libraries, annotation processors).

#### Fixed
- **Silent subprocess failure**: when `mvn`/`gradle` is absent or its invocation fails, JavaLens previously returned an empty classpath with no signal. The `load_project` response now surfaces structured `LoadWarning` entries (`MAVEN_SUBPROCESS_FAILED`, `MAVEN_SUBPROCESS_TIMEOUT`, `GRADLE_SUBPROCESS_FAILED`, `COMPLIANCE_LEVEL_UNKNOWN`) so AI agents see degraded analysis explicitly.
- **Multi-module Maven**: per-reactor-child classpath aggregation. The original `dependency:build-classpath -Dmdep.outputFile=<absolute>` made every child overwrite the same file, so only the last module's classpath survived. Each module now writes to its own `target/javalens-classpath.txt`; results are union-merged via `LinkedHashSet`.
- **Multi-module APT**: `<annotationProcessorPaths>` declarations in child poms (typical pattern: `:model` declares Lombok) were missed because only the root pom was parsed. Now walks the reactor recursively.
- **Multi-project Gradle**: `settings.gradle` `include 'subproject'` directives are parsed; subproject `src/main/java` and `build/generated/sources/<task>/main/java` are added as source folders so types declared in subprojects resolve.
- **Bazel multi-target source discovery**: with the `<target>/src/main/java/...` layout, `BUILD.bazel` sat several levels above the Java sources and the existing scan returned nothing. Source roots now derive from every `BUILD.bazel` package.
- **Bazel `bazel-bin`/`bazel-out` dedup**: `bazel-bin` is typically a symlink whose target lives under `bazel-out`. Scan roots are canonicalized via `Path.toRealPath()` and jars dedupe by canonical path; previous scans double-counted every jar.
- **Bazel symlink scan platform divergence**: on Linux/macOS, `bazel-bin` and `bazel-out` are real symbolic links whereas on Windows they are junctions. `Files.walk` without `FOLLOW_LINKS` treats a Linux symlink-to-directory as a non-directory single entry — so the previous scan, which kept the original symlink path after canonicalization, descended on Windows but emitted zero entries on Linux/macOS. `canonicalizeAndDedupe` now returns canonical paths so the walk always starts at a real directory.
- **Nested aggregator Maven modules** (issue #8 — reported by external user): a child module that is itself an aggregator (`<packaging>pom</packaging>` with `<modules>` and no `src/main/java`) caused source discovery to stop at depth 1. Sources under `core/core-lib`, `core/core-api`, etc. were never indexed. `getAllSourcePaths` is now recursive, with cycle protection via canonical-path visited set against relative `<module>` entries that loop.
- **JDT search index race**: `loadProject` returned before JDT's background indexer finished, so the first `find_references`/`search_symbols` call after load could miss results. `loadProject` now flushes the indexer with `WAIT_UNTIL_READY_TO_SEARCH`.
- **Compiler compliance silently inherited workspace defaults**: `JavaCore.COMPILER_SOURCE`/`COMPLIANCE`/`CODEGEN_TARGET` now read from `maven.compiler.release`/`source`/`target`, Gradle `sourceCompatibility`, or Bazel `javacopts` (`-source`/`-target`/`--release` / `--release=N`). Java 21 record patterns and similar level-specific syntax now parse correctly.
- **Generated source directories absent from classpath**: annotation-processor outputs at `target/generated-sources/<processor>/` (Maven) and `build/generated/sources/<task>/main/java` (Gradle) are now discovered as source folders, so references to generated symbols (Lombok getters, MapStruct mappers, JPA metamodel) resolve.

#### Added
- **Gradle classpath support**: replaces the `getGradleDependencies` stub that returned `List.of()`. Ships an init script that registers a `javalensWriteClasspath` task on every Java subproject, runs it via the project's Gradle Wrapper or `gradle` on `PATH`, then unions the resulting `build/javalens-classpath.txt` files. Compile/test/runtime configurations are unioned so `compileOnly` deps (Lombok and similar) reach JDT.
- **JDT annotation processing wiring**: `org.eclipse.jdt.apt.core.util.AptConfig.setEnabled(true)` plus per-processor jar registration on the factory path. Maven processor jars resolve from pom `<annotationProcessorPaths>` against `~/.m2/repository`; Gradle processors come from the `annotationProcessor` configuration via the init script. For Bazel — which has no equivalent processor-path declaration that we parse — JavaLens scans the resolved Bazel classpath jars for `META-INF/services/javax.annotation.processing.Processor` and auto-registers any that match. The scan runs only on the Bazel path; Maven and Gradle continue to use their build-file-driven detection so we don't double-register.
- **Plain Java compliance fallback**: projects with no build file fall back to `Runtime.version().feature()` rather than silently inheriting older JDT defaults.
- **`org.eclipse.jdt.apt.core` bundle** added to imports so APT wiring works at runtime.

#### Test infrastructure
- 12 new fixtures under `org.javalens.core.tests/test-resources/sample-projects/` covering single-module, multi-module, generated sources, compliance mismatch, broken-deps, Lombok APT, and realistic three-module representative projects per build system.
- 11 new `buildsystem/` test classes plus end-to-end integration tests (`EndToEndIntegrationTest`, `EndToEndGradleIntegrationTest`, `EndToEndBazelIntegrationTest`) that load a representative project and assert every fix is exercised in a single pass.
- New MCP-tool-level tests: `LoadProjectToolTest` extended with warnings-array shape checks; new `CrossModuleNavigationToolTest` exercises `find_references` across reactor modules through the actual `tool.execute(args)` JSON-RPC entry point an AI agent uses.
- `TestEnvironment.requireOrSkip` helper: tests skip on missing tools locally; setting `JAVALENS_TESTS_REQUIRE_TOOLS=true` flips them to hard failures so CI cannot silently weaken coverage.
- Subprocess test helpers (`runMaven`/`runBazel` across all integration tests) now capture stdout into a bounded buffer and include it in the failure exception so CI failures are debuggable without the runner artifact upload.
- CI workflow installs Maven, Gradle, and `bazelisk` on Linux/macOS/Windows and runs with `JAVALENS_TESTS_REQUIRE_TOOLS=true`. Triggers on push to master in addition to PRs.
- Gradle install unified across all three OSes via the vendor-supported `gradle/actions/setup-gradle@v3` action pinned at 8.10.2. Replaces `apt-get install gradle` (Ubuntu's Gradle 4.x is too old for the init script) and choco's gradle.bat (didn't propagate reliably to the surefire JVM on Windows).
- Maven on Windows switched from `choco install maven` to direct Apache distribution download and extract. Choco's `mvn.cmd` launcher tries to read `lib/jvm.cfg` from the JDK, which Temurin (the distribution `setup-java` provides) doesn't ship, so the launcher fails before reaching Maven itself. Direct extract gives a launcher that works with any JDK that has `bin/java`.

#### Documentation
- README build-system support tables expanded from "detection markers only" to capability matrices showing what each system reads from build metadata (multi-module, compliance, generated sources, APT). Architecture diagram converted to Mermaid for native GitHub rendering. Stale tool count and unbenchmarked perf claim removed.

## 1.2.0 - 2026-04-08

### Added
- 7 new analysis tools (Tool count: 56 → 63)
  - `analyze_change_impact` — blast radius assessment for symbol changes
  - `analyze_data_flow` — variable read/write/declaration tracking within methods
  - `analyze_control_flow` — branching, loops, return/throw points, nesting depth
  - `get_di_registrations` — Spring DI registration scanning (@Component, @Bean, @Autowired, @Inject)
  - `find_reflection_usage` — detect Class.forName(), Method.invoke(), and other reflection calls
  - `find_large_classes` — find types exceeding configurable size thresholds
  - `find_naming_violations` — check against Java naming conventions

## 1.1.5 - 2026-04-08

### Added
- Published to official MCP Registry (registry.modelcontextprotocol.io)
- Added `mcp-server` keyword to npm package for discoverability

## 1.1.4 - 2026-04-04

### Changed
- Updated npm package README with full feature documentation

## 1.1.3 - 2026-04-04

### Changed
- npm package now bundles the full distribution instead of downloading at runtime
  - No network dependency after install, no cache corruption, no version mismatch
- Added IGNORED_DIRS to skip .git, node_modules, target, etc. during Bazel source scanning
- Added slf4j.simple logging binding to product for production log visibility

## 1.1.2 - 2026-03-26

### Added
- Bazel `bazel-out/` directory scanning for dependency JARs
- Documented `JAVA_TOOL_OPTIONS` for JVM memory configuration on large projects

## 1.1.1 - 2026-03-25

### Added
- npm package distribution (`npx -y javalens-mcp`)
  - Downloads and caches the JavaLens distribution on first run
  - Verifies Java 21+ before launching
  - Reads JVM arguments from eclipse.ini automatically

### Fixed
- Updated all project versions from 1.0.0 to 1.1.1 across pom.xml, MANIFEST.MF, and product files
  - Previously version was stuck at 1.0.0 in source while releases used git tags

## 1.1.0 - 2026-03-25

### Added
- Bazel build system support
  - Detects projects via `MODULE.bazel`, `WORKSPACE.bazel`, and `WORKSPACE` markers
  - Scans `bazel-bin/` for dependency JARs
  - Discovers source directories in Bazel-native layouts (Java files alongside BUILD files)
  - Falls back to standard source layouts (`src/main/java`) when present
- 6 new tests for Bazel detection and source scanning

### Changed
- README clarifies Java 21 is the server runtime, not an analysis restriction
  - JavaLens analyzes Java source from version 1.1 through 23

## 1.0.2 - 2026-01-03

### Fixed
- Fixed MCP connection timeout with `JAVA_PROJECT_PATH` auto-load
  - Project now loads asynchronously in background
  - Server responds to MCP `initialize` immediately instead of blocking
  - No more 30-second timeout failures for large projects

### Added
- Loading state tracking via `health_check`
  - New `project.status` field: `not_loaded`, `loading`, `loaded`, `failed`
  - Tools return informative "Project is loading, please wait..." message during async load
- `ProjectLoadingState` enum for tracking load progress
- `ToolResponse.projectLoading()` and `ToolResponse.projectLoadFailed()` for better error messages

### Changed
- `HealthCheckTool` now reports detailed loading state instead of just loaded/not-loaded
- `AbstractTool.execute()` provides specific feedback based on loading state

## 1.0.1 - 2026-01-01

### Fixed
- Fixed race condition when multiple MCP instances start simultaneously
  - Session UUID now injected into workspace path before OSGi framework starts
  - Each session gets isolated workspace, preventing "workspace locked" errors

### Changed
- Replaced native launcher with Java wrapper JAR
- Single distribution for all platforms (no more platform-specific archives)
- Invocation changed from `javalensc` to `java -jar javalens.jar`

## 1.0.0 - 2025-12-31

Initial release of JavaLens MCP Server.

- 56 MCP tools for Java code navigation, analysis, refactoring, and search
- Eclipse JDT Core for semantic analysis
- Maven and Gradle project support
- 347 tests
