# Plan: True In-Process Session Multiplexing over HTTP

## Goal

Run one long-lived JavaLens server process that handles many concurrent MCP
sessions over HTTP, instead of today's model (one OS process per stdio
connection, one project loaded per process).

**Revised design goal (see "Project sharing" below):** sessions do not each
load their own copy of a project. A project is loaded once per canonical
path and shared by every session pointed at that path — the point is to
avoid paying a full reload every time a new session attaches to a codebase
that's already loaded, which is the actual pain point driving this work
(many sessions in practice target the same checkout). Two sessions pointed
at two genuinely different paths still each get their own loaded project;
sessions pointed at the *same* path share one.

## Current architecture (verified in code)

- `McpProtocolHandler.processMessage(String) -> String`
  (`org.javalens.mcp/src/org/javalens/mcp/protocol/McpProtocolHandler.java`)
  is already a pure JSON-RPC function with no transport dependency. Good
  starting point, but it also holds **instance state**: `initialized`,
  `clientName`, `clientVersion`. Today there's exactly one instance per
  process; multiplexing needs one per session.
- `JavaLensApplication` (`org.javalens.mcp/src/org/javalens/mcp/JavaLensApplication.java`)
  owns the singleton state that everything else reaches into:
  - instance fields `jdtService`, `loadingState`, `loadingError`
  - a static `instance` field with static accessors
    `getLoadingState()` / `getLoadingError()`, called directly from
    `AbstractTool.execute()` (`tools/AbstractTool.java:90-94`)
  - `registerTools()` constructs ~80 `Tool` objects, every one closing over
    the same `() -> jdtService` lambda.
  - `runMessageLoop()` is the only stdio-specific piece: a single-threaded
    `BufferedReader`/`PrintWriter` loop.
- `JdtServiceImpl` (`org.javalens.core/src/org/javalens/core/JdtServiceImpl.java`)
  itself is **not** a singleton in the type system — all state (`javaProject`,
  `projectRoot`, `searchService`, etc.) is instance-level. Nothing stops
  creating several instances side by side.
- `WorkspaceManager` (`org.javalens.core/src/org/javalens/core/workspace/WorkspaceManager.java`)
  was already built with multi-session isolation in mind: each instance
  generates its own UUID and names its Eclipse project
  `"{baseName}-{sessionId}"`, explicitly so "multiple concurrent sessions"
  can coexist in one Eclipse workspace root. This is most of the hard part
  of multi-tenancy already done.
  - **Bug that blocked real concurrency (fixed in Phase 3)**:
    `sweepStaleProjects()` used to delete every workspace project matching
    `{baseName}-[a-f0-9]{8}` except the current session's own — including
    other sessions' *live* projects if two sessions happened to load
    directories with the same base name. It's been removed entirely; see
    Phase 3 below for what replaced it.
- `JreInstallEnsurer` (`org.javalens.core/src/org/javalens/core/project/JreInstallEnsurer.java`)
  already registers the running JVM's `IVMInstall` under a deterministic,
  idempotent id ("regardless of how many JavaLens sessions run" per its own
  doc comment) — no change needed here.
- Search/graph/disk-sync services (`SearchService`, `ProjectGraphService`,
  `DiskStampService`) are instance-scoped and the mutating methods are
  already `synchronized` per instance — safe to run N of them in parallel
  threads, one per session.

Net assessment: the workspace/JDT layer was already designed for multi-session
coexistence. The actual gap is entirely in the MCP-server layer
(`org.javalens.mcp`): singleton `jdtService`/loading-state fields, one
`McpProtocolHandler` per process, and no session concept in the transport.

## Design

### Project sharing: registry keyed by canonical path

The load-once-per-project-not-per-session decision changes where state
lives. A project's `IJdtService`/loading-state is no longer owned by a
`Session` — it's owned by a `LoadedProject`, keyed in a `ProjectRegistry` by
canonical (absolute, normalized) path. Sessions *attach* to a `LoadedProject`
by path; multiple sessions pointed at the same path share the same one.

New `LoadedProject`:
- `canonicalPath`
- `IJdtService` (null while `LOADING`), `ProjectLoadingState`, loading error
- a ref count (sessions currently attached) — the eviction signal
- a serialization primitive (`ReentrantLock`, exposed as `runExclusive`) so
  concurrent requests from *different sessions attached to the same
  project* don't race each other. This matters most for mutating
  operations — refactorings, `ensureFresh()`'s disk-sync repair, a reload —
  since JDT's model isn't provably safe under concurrent writers.
  Concurrent read-only queries across sessions on the same project is a
  documented future optimization (a read/write split), not implemented
  here — don't build it speculatively before there's a reason to.
- `lastAccessedAt`, touched on attach/detach, for idle eviction once the
  ref count hits zero

New `ProjectRegistry`:
- `ConcurrentHashMap<Path, LoadedProject>`
- `attach(Session, Path)`: canonicalizes the path; reuses an
  already-loaded-or-loading entry for that path if one exists (increments
  its ref count), otherwise creates one and kicks off exactly one
  `loadProject()` asynchronously (mirrors today's
  `JavaLensApplication.autoLoadProjectFromEnv` pattern, just keyed by path
  instead of a single field). If the session was already attached
  elsewhere, detaches it from the old project first. Re-attaching to the
  path a session is already on is a no-op.
- `detach(Session)`: decrements the ref count of whatever the session was
  attached to.
- background idle-sweep evicts projects with ref count zero that have sat
  idle past a timeout: disposes the `IJdtService`'s Eclipse project via
  `WorkspaceManager.deleteProject` (through `IJdtService.dispose()`), drops
  the map entry.
- the attach-or-create-and-increment step and the evict-if-idle-and-unused
  step must be two ends of the same atomic map operation (`compute` /
  `computeIfPresent` on the same key), not two separate read-then-act
  steps — otherwise a session can attach to a `LoadedProject` the sweep is
  concurrently evicting, or the sweep can evict one a session just
  attached to. Both operations lock the same `ConcurrentHashMap` bin for
  the same key, which is what actually closes the race.
- replaces `sweepStaleProjects`'s cleanup job entirely — see Phase 3

`Session` shrinks to almost pure client-handshake state:
- `sessionId` (server-generated, e.g. UUID)
- its own `McpProtocolHandler` instance (so `initialized`/`clientName`/
  `clientVersion` are per-session, matching MCP semantics — each client
  does its own handshake)
- a reference to the `LoadedProject` it's currently attached to (or null
  before the first `load_project` call) — `getJdtService()`/
  `getLoadingState()`/`getLoadingError()` become thin delegations to
  whatever's attached, defaulting to null/`NOT_LOADED`/null when nothing is
  attached yet
- `lastAccessedAt` for the session's *own* idle eviction (a session with no
  activity is cheap to reclaim now — it isn't holding a loaded project by
  itself, `ProjectRegistry` is)

`SessionManager` keeps the same responsibilities as before (`create()`,
`get(id)`, `terminate(id)`, idle sweep) but now also holds a `ProjectRegistry`
reference so `terminate()` can `detach()` the session's project instead of
disposing an `IJdtService` it no longer owns directly.

**Auto-attach note (forward-looking, not built yet):** today's
`JAVA_PROJECT_PATH` auto-load-at-startup behavior should probably mean a
*new* session auto-attaches to that already-loaded project without its own
`load_project` call, rather than starting unattached until the client calls
`load_project` again for a path that's already loaded. That's a startup/
transport policy decision (Phase 2/5), not something `ProjectRegistry`
itself needs to decide.

### Threading the session through existing code

The ~80 `() -> jdtService` lambdas in `registerTools()` don't need
per-tool edits. Route them all through one indirection point:

```java
toolRegistry.register(new SearchSymbolsTool(this::currentJdtService));
// ...
private IJdtService currentJdtService() {
    Session s = SessionContext.current(); // ThreadLocal<Session>, set by the transport
    return s == null ? null : s.getJdtService(); // delegates to the attached LoadedProject
}
```

Same treatment for the two static calls in `AbstractTool.execute()`
(`JavaLensApplication.getLoadingState()/getLoadingError()`): replace with
`SessionContext.current().getLoadingState()/getLoadingError()` (also
delegating through to the attached `LoadedProject`).

The HTTP transport is responsible for binding `SessionContext` to the
resolved `Session` before calling `protocolHandler.processMessage(...)`,
and clearing it afterward (try/finally around the dispatch, since it's a
`ThreadLocal` and the thread returns to a pool). Where `AbstractTool`
previously would have called `session.runExclusive(...)`, it now needs
`session.getAttachedProject().runExclusive(...)` (null-checked — no project
attached yet means nothing to serialize against, e.g. before the first
`load_project`).

### HTTP transport

Implement MCP's Streamable HTTP transport, the minimum viable slice:
- Single endpoint, e.g. `POST /mcp`.
- `initialize` response carries a fresh `Mcp-Session-Id` response header;
  every subsequent request from that client must send it back.
- Missing/unknown `Mcp-Session-Id` on a non-initialize request -> 404,
  per spec (client must re-initialize).
- Respond with plain `application/json` (not SSE). Nothing in this
  codebase sends unsolicited server->client notifications today, so the
  SSE stream (`GET /mcp`) that Streamable HTTP allows for server push is
  not required for feature parity with the current stdio behavior. Treat
  it as a later addition, not a blocker.
- Optional `DELETE /mcp` with `Mcp-Session-Id` for explicit termination
  (maps directly onto `SessionManager.terminate`).
- Implementation: JDK's built-in `com.sun.net.httpserver.HttpServer`
  (`jdk.httpserver` module) avoids adding a new heavyweight dependency.
  **Risk to validate early**: this package must be visible to the
  `org.javalens.mcp` OSGi bundle. It's a standard JRE package, but Equinox
  still needs it either auto-exported by the system bundle or added to
  `Import-Package` — spike this before committing to the approach; fall
  back to embedding Jetty (`org.eclipse.equinox.http.jetty` is already a
  natural fit for this Eclipse/Equinox stack) if the system package isn't
  visible.
- Runs on a bounded thread pool; each incoming request resolves its
  `Session` (creating one on `initialize`), binds `SessionContext`, calls
  `protocolHandler.processMessage`, unbinds, writes the HTTP response.

### Dual-mode entry point

Keep the existing single-session stdio path completely intact (zero risk
for current users): branch in `JavaLensApplication.start()` on e.g.
`JAVALENS_TRANSPORT` (default `stdio`). `stdio` mode keeps today's code
path unchanged, including its single implicit "session" backed by the
existing singleton-ish fields (or, if it's a clean refactor, stdio mode
just becomes "a SessionManager with exactly one session and no HTTP
listener" — decide during implementation based on which is less invasive).
`http` mode starts the `SessionManager` + HTTP listener instead of
`runMessageLoop()`.

## Phased implementation

1. **Session + project-sharing model** — add `Session`, `LoadedProject`,
   `ProjectRegistry`, and `SessionManager` (no wiring into
   `JavaLensApplication`/tools yet). Unit tests for session create/get/
   touch/evict; `LoadedProject` ref-counting and `runExclusive` serialization;
   `ProjectRegistry.attach`/`detach` reuse-by-path behavior, concurrent
   attach() calls to the same path never triggering two loads, and the
   attach-vs-evict race described above.
2. **Decouple singleton state** — introduce `SessionContext` (ThreadLocal),
   change the tool-registration lambdas and `AbstractTool.execute()`'s two
   static calls to go through it. Stdio mode binds a single ambient
   session for the process lifetime, so behavior is provably unchanged for
   existing users. Add a test asserting stdio golden-path output is
   byte-identical before/after this refactor.
3. **Fix `WorkspaceManager.sweepStaleProjects` — done.** Removed the
   cross-session sweep and the method entirely; `WorkspaceManager`'s class
   Javadoc now documents that cleanup is always explicit
   (`deleteProject`/`IJdtService.dispose()`), never an implicit
   name-pattern sweep. Removing the sweep opened a companion gap that
   needed closing in the same change: `LoadProjectTool` used to rely on the
   sweep to reclaim the *previous* project's workspace entry whenever
   `load_project` was called a second time in the same session (a real,
   common case — not just a crash-recovery path). `LoadProjectTool` now
   takes a `Supplier<IJdtService>` alongside its existing
   `Consumer<IJdtService>` and disposes the outgoing service itself, but
   only *after* the new one has loaded successfully — a failed reload
   leaves the previous project registered and intact, matching today's
   failure-mode behavior. Updated `WorkspaceManagerTest` (the two tests
   that pinned the old sweep-deletes / sweep-scoped-to-basename behavior
   are replaced with a test asserting a same-base-name sibling project now
   *survives*) and `LoadProjectToolTest` (added: reload disposes the
   replaced service; a failed reload disposes nothing and leaves the
   previous service in place). All five call sites in
   `org.javalens.mcp.tests/.../protocol/*IntegrationTest.java` and the
   production call site in `JavaLensApplication.registerTools()` were
   updated for the new two-arg constructor.
4. **Per-project concurrency control — done.** `AbstractTool.execute()` now
   resolves the ambient session's attached `LoadedProject` (via
   `SessionContext.current()`) and, when one is attached, runs the disk-sync
   verification (`ensureFresh()`) and `executeWithService()` inside its
   `runExclusive` — the lock lives on `LoadedProject`, not `Session`, since
   it's the project that's shared. No project attached (shouldn't happen once
   `service` is non-null, since a non-null service implies an attached
   project) falls back to running unlocked rather than throwing. Added
   `AbstractToolConcurrencyTest`: one test attaches two sessions to the same
   `LoadedProject` (via `ProjectRegistry.registerLoaded` + `attach`) and
   asserts a slow `executeWithService` body never observes more than one
   concurrent caller inside the locked section; a second test attaches two
   sessions to two different projects and asserts both run inside the section
   at once (no cross-project serialization). `LoadedProjectTest`'s existing
   `runExclusive` test already pinned the lock's own behavior in isolation —
   this closes the gap that nothing production-side actually called it yet.
5. **HTTP transport** — implement the `POST /mcp` (+ optional `DELETE`)
   handler described above; spike the `com.sun.net.httpserver` OSGi
   visibility question first and fall back to
   `org.eclipse.equinox.http.jetty` if needed. Integration test: two
   concurrent HTTP clients, `Mcp-Session-Id` handling, missing/unknown
   session id -> 404.
6. **Lifecycle & resource management** — idle-session TTL sweep, a cap on
   concurrent sessions (reject or LRU-evict beyond it — memory of N
   simultaneous JDT models in one JVM is the real ceiling here, not CPU),
   and a way to observe active sessions (extend `health_check` or add an
   admin-only tool listing session id / project / age).
7. **Dual-mode entry point** — wire `JAVALENS_TRANSPORT` in
   `JavaLensApplication.start()`; update `README.md`/`server.json` to
   document the new transport option.
8. **Tests** — beyond the per-phase tests above: a soak test for idle
   eviction, and a check that `JreInstallEnsurer`'s idempotent registration
   and JDT's search/index machinery genuinely tolerate several projects
   being queried in parallel (the design implies they should; verify it
   under real concurrent load rather than trusting the design).

## Open risks to flag before/while building

- **Memory**: today, crashing/OOMing one session only costs one process.
  With multiplexing, one JVM hosts every active *project* — a single
  runaway analysis (huge project, pathological query) can degrade or take
  down every session attached to it (and, since it's one JVM, potentially
  every session on every project). The shared-by-path registry improves
  this versus the original per-session design — N sessions on the same
  path cost memory once, not N times — but distinct concurrent projects
  still each cost their own footprint. Needs a concurrent-*project* cap
  and resource awareness from day one, not as a follow-up.
- **Same-path concurrent loads**: resolved by design now, not just
  mitigated — `ProjectRegistry.attach()` reuses an in-flight or already-loaded
  entry for a given canonical path instead of triggering a second
  `loadProject()`, so the `GradleImporter`/`MavenImporter` fixed-named
  classpath file race (`javalens-classpath.txt` etc., written into the
  target project's own build directory) no longer has two concurrent
  writers for the same path. It can still race if something *outside*
  JavaLens (e.g. a real IDE, or a second unrelated JavaLens process) loads
  the same path at the same time — out of scope here.
- **Auth/exposure**: stdio sessions are only reachable by whatever spawned
  the process (trusted parent). An HTTP listener is a network-reachable
  surface that can read/refactor/write source files. Bind to localhost by
  default and require at minimum a bearer token or similar before this
  ships anywhere beyond a developer's own machine.
- **SSE/server push**: skipped in the initial plan since nothing in the
  server currently originates unsolicited notifications; revisit if a
  future feature needs to push (e.g., long-running background index
  progress).
