# qits-observability — working notes

Read `README.md` first: it defines the boundary (receiver vs. producers), lists the routes and the
ports. This file is the working conventions on top of it.

## The one rule that shapes everything

This repo must build and test green from a **clone of itself alone** — no monorepo, no docker, no
network, no prior `mvn install` elsewhere, no credentials. `mvn verify` is the gate. Anything that
would break that is not a tradeoff to weigh, it is the thing this repo exists to avoid.

That is why: the poms duplicate versions instead of inheriting them, the cross-context scoping
checks are ports with fakes in `src/test` rather than a real projects/workspaces database, and
`OtelStubTestResource` runs a `com.sun.net.httpserver.HttpServer` on an ephemeral port instead of
reaching a real OTLP collector. **Never make the suite depend on a live collector, on port 4317/4318,
or on the network.** `OtelTeeUnreachableTest` deliberately points at `http://localhost:1`, and that
must stay a fast connect-refused, not a timeout.

## Package and module conventions

`eu.wohlben.qits.telemetry.*`, one maven module, sub-packages `api` / `control` / `dto` / `mcp` /
`error`. The module directory is `service/` and the artifactId is `qits-telemetry`; they disagree on
purpose (see the poms' header comments — directories are the git-history anchor, artifactIds are the
settled name).

There is no `domain/` module and no reason to add one: nothing here is persisted, so the usual split
(framework-free entities + persistence + Flyway in `domain`, web stack in `service`) has nothing to
separate. If this context ever grows a table, split it then.

`api/` holds JAX-RS. `control/` holds the store, the decoder and the query service, and stays free
of JAX-RS annotations so it can be unit-tested without booting Quarkus — `TelemetryStoreTest` and
`TelemetryDecoderTest` are plain JUnit and should stay that way.

## Adding a dependency on another context

Don't. Declare a port in the package that needs it, inject it as `Instance<T>`, and make absent a
supported configuration with a documented behaviour — see the table in the README.

For the two existing ports the documented behaviour is **fail closed**, not "skip the check": they
are cross-project isolation boundaries. A future port that only enriches a response may of course
degrade gracefully instead; say which in its javadoc.

Never add a JPA relation to another context's entity, and never add an entity here at all without
first re-reading "Owns no tables" in the README. Telemetry references repositories and workspaces by
the string ids an exporter stamped into its resource attributes; those ids are not validated at
ingest and are not foreign keys.

## The buffer

`TelemetryStore`'s lock order is always `evictionLock → bucket monitor`, and appenders never take
`evictionLock` while holding a bucket monitor. Keep it that way; the two bounding tiers can
otherwise deadlock. Every mutation path must also stay byte-accounted — `account()` is called with a
negative delta on every eviction, and a missed one leaks the global ceiling.

Anything appended fires at most one `TelemetryChanged` per distinct scoped workspace per call. Do
not fire per record; a 1000-span batch is one event by design.

## Authentication

Authentication happens at `qits-gateway`. This service resolves a principal from a trusted header
(`X-Qits-User`, read by `telemetry/security/ForwardAuthMechanism`) and authenticates nothing.

**`identity.isAnonymous()` is not a security state** — it means "no name for the audit row". A check
of the form `if (identity.isAnonymous()) deny` would look like a security control and be worth
nothing, because reaching this service at all already implies you are inside the trusted network.

There is no auth variant to select and no authorization policy here, and roles are deliberately not
resolved — the single role check the system has (`qits.auth.required-role`) is the gateway's. See
`migration-auth-plan.md`.

## Tests

- Register scope with `FakeRepositoryScopeGuard.allow(repoId)` and
  `FakeWorkspaceLookup.register(repoId, workspaceId)`. Both are `@ApplicationScoped` beans in
  `src/test/java`, so they are present in every `@QuarkusTest` here; reset them in `@BeforeEach`
  alongside `store.clear()`.
- `TelemetryFixtures` builds real `Export*ServiceRequest` protobufs. Seed the store through the real
  `TelemetryDecoder` rather than hand-constructing `StoredSpan`s where the decoding is part of what
  you're asserting.
- App-level config lives in `src/main/resources/application.properties` —
  `quarkus.rest.path=/observability/api`, `quarkus.http.non-application-root-path=/observability/q`,
  the MCP root-path, the body limit, the OpenAPI info — and **the tests inherit it**. Quarkus merges
  main's copy into the test config rather than letting `src/test/resources/application.properties`
  shadow it, so the suite exercises the values that actually ship. Never re-declare an app-level key
  in test resources: the copy drifts, and the suite goes on asserting `/observability/*` while the
  packaged process serves something else. `src/test/resources/application.properties` is for values
  a test run genuinely needs to be *different*, and today there are none.
- `OpenApiSchemaExportTest` writes `docs/openapi.yml` as a side effect. Regenerate and commit it
  whenever the REST surface changes:

      ./mvnw -pl service test -Dtest=OpenApiSchemaExportTest

  It runs as a `@QuarkusTest`, so **the test classpath is indexed too**: any `@Path` resource under
  `src/test` lands in the committed document unless it is `@Operation(hidden = true)`. That is why
  `IdentityEchoResource` carries the annotation. The document should hold exactly the five
  telemetry query operations — ingest is hidden on purpose.
- There are no integration tests and nothing here needs docker, so `mvn verify` is runnable
  anywhere. Keep it that way.
- **A `Failed to start quarkus` / `Port already bound: 8081` failure is the known flake**
  (`migration-plan.md` §9 item 14), not your change: `@QuarkusTest` restarts race for the test port.
  Re-run before investigating.

## What is not ours to change

The gateway's `QitsService.OTEL` constant and its `qits-otel` default host still carry the retired
third name. Reconciling them is the gateway's change, tracked as migration-plan.md §9 items 1 and 9.
