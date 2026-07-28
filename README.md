# qits-observability

The **telemetry** context of qits: an in-process OTLP/HTTP receiver, a bounded in-memory buffer of
what it receives, and a query surface over that buffer for both humans (REST) and coding agents
(MCP). Plus the managed-app relay that goes with it, the upstream OTLP tee.

Everything it serves lives under **`/observability`** — `qits-gateway` routes verbatim by prefix, so
the segment is part of the path this process itself serves, on `qits-net` as much as through the
gateway. There is no unprefixed form.

    mvn verify        # a clone of this repo alone builds and tests green — no monorepo, no docker
    mvn verify -Dnative   # and compiles to a GraalVM binary, still no docker (see .sdkmanrc)

Services launched inside a workspace with the `otel` toggle get `OTEL_EXPORTER_OTLP_ENDPOINT` and
`OTEL_RESOURCE_ATTRIBUTES` pointed back at qits. Their spans, logs and metrics land here, bucketed
by the `qits.repository.id` / `qits.workspace.id` attributes they carry. An agent debugging that
workspace then asks `telemetryErrors` instead of scraping logs, and gets exceptions as structured
span events with stack traces, correlated by trace id.

## Two names, on purpose

`qits-observability` is the **repo, the bounded context, the deployable** — the gateway route and
the submodule are named for it. `qits-telemetry` is the **maven module and java package inside
it** — `eu.wohlben.qits.telemetry.*`. Settled in the superproject as
`1919396 Settle the observability naming question`. The earlier `qits-otel` (the seed README, the
gateway's `OTEL` enum constant and its default `qits-otel` host) is retired; reconciling the
gateway constant belongs to the gateway.

## Layout

| Path | What |
|---|---|
| `service/` | The whole context, artifactId `qits-telemetry`. |
| `…/api/` | `OtelReceiverResource` (OTLP ingest), `OtelForwarder` (the upstream tee), `WorkspaceTelemetryController` (the UI's JSON), `TelemetryExceptionMapper`. |
| `…/control/` | `TelemetryDecoder` (protobuf → records), `TelemetryStore` (the buffer), `TelemetryQueryService` (every query both surfaces answer from), `TelemetrySizeEstimator`, `TelemetryChangePublisher`. |
| `…/dto/` | The stored records and the wire DTOs. |
| `…/mcp/` | `TelemetryMcpTools` (five tools on the `observability` MCP server), `TelemetryToolFilter`, `RepositoryScope`, `WorkspaceScope`, and the two ports. |
| `…/error/` | This context's own `DomainException` family (migration-plan.md §5). |

One module, not the usual `domain/` + `service/` pair. This is the only qits context whose business
logic already lived entirely in the monorepo's `service` module (migration-plan.md §3.6) — there is
no `domain/telemetry` to replay. The directory is still called `service/` because the replayed git
history is anchored to `service/src/**`.

**An application, not a library jar.** `service/` carries `<packaging>quarkus</packaging>` and
produces a process that receives OTLP on its own port — as a JVM fast-jar or as a native binary. It
was extracted as a library on the assumption that some consuming Quarkus application would pull it
in and gain the receiver; no such application was ever written, and under the gateway topology none
will be. A receiver that cannot be started is not a receiver.

    ./mvnw verify
    java -jar service/target/quarkus-app/quarkus-run.jar   # :8080, ingest on /observability/api/otel/v1/*

    ./mvnw package -Dnative
    ./service/target/qits-observability                    # same routes, ~30ms to listening

**Native is the shipping form.** `.sdkmanrc` names a GraalVM (`25.0.2-graalce`) so `sdk env` alone
is enough toolchain: the build wants a `native-image` on `GRAALVM_HOME`, `JAVA_HOME` or `PATH`, and
if it finds none it does not fail — it quietly falls back to pulling a 1.8 GB Mandrel image and
compiling under docker. That fallback still works and is what a CI without a GraalVM gets; it is
just not the intended path, and it is worth recognising by name when a build that normally takes
about 80 seconds starts downloading a container image.

`-Dnative` also flips `skipITs`, so the build runs `OtelReceiverIT` against the binary it just
compiled. That is not ceremony: this service's whole ingest surface is generated protobuf, which
native-image has to resolve ahead of time, and a mistake there is invisible to the JVM suite and
lands as a runtime failure on the first export. The IT posts real OTLP bodies and reads them back
out through the query surface, so a 200 on bytes that decoded to nothing cannot pass.

## What it owns, and what it deliberately does not

**Owns no tables, and no datasource.** `TelemetryStore` is in-memory and ephemeral by design — a JVM
restart empties it. Bounding is two-tier: per-workspace count caps (spans / logs / metric series)
plus a global byte ceiling that evicts oldest-first from the *fattest* bucket, so one chatty service
pays for its own volume instead of evicting a quieter workspace's telemetry. Tuning knobs default in
code: `qits.telemetry.max-spans-per-workspace` (5000), `.max-logs-per-workspace` (10000),
`.max-metric-series-per-workspace` (500), `.max-total-bytes` (64 MiB).

**Ingest is protobuf-only.** qits pins every launched exporter to `http/protobuf`, so OTLP/JSON
(which deviates from proto3 JSON) and gRPC are not implemented. Gzip is detected by magic bytes
rather than `Content-Encoding`, which is correct whether or not the server already decompressed.

**It does not produce telemetry.** `quarkus-opentelemetry` — qits' own outbound SDK — is not a
dependency here; that is the app shell's business. This repo is the receiving end.

**It does not know what a workspace is.** Records are bucketed by the resource attributes an
exporter stamped, nothing more. Telemetry whose attributes are missing lands in a quarantine bucket
(`_unscoped`) that is bounded like any other and exposed by no query.

## The boundary

Everything this context needs from the rest of qits goes through a port it declares and the
consuming application implements. Cross-context references are by string id, never a foreign key.

| Port | Required? | Absent means |
|---|---|---|
| `RepositoryScopeGuard` | no | the telemetry MCP tools are **hidden and rejected** (fail closed) |
| `WorkspaceLookup` | no | likewise |

Both are the MCP scoping checks: "is this repository inside the session's project?" (the projects
context's answer) and "is this still an active workspace of that repository?" (the workspaces
context's answer). They are cross-project isolation checks, so *unconfigured* must never degrade to
*unchecked* — without them `TelemetryToolFilter` does not list the tools and a direct call gets a
404. Ingest, the store and the REST query surface need neither and work standalone.

In the other direction this context publishes `TelemetryChanged(repoId, workspaceId)` as a CDI async
event whenever a scoped ingest fills a workspace's buffers (deduped — a 1000-span batch for one
workspace is one event). An application that also runs the workspaces context bridges it to the SSE
channel:

```java
void onTelemetry(@ObservesAsync TelemetryChanged changed) {
  workspaceChangePublisher.fire(changed.repoId(), changed.workspaceId(), Topic.TELEMETRY);
}
```

With no observer the event is a no-op, which is the supported standalone configuration: browsers
just re-read on their own schedule instead of being pushed at. The event carries no payload, so a
dropped one self-heals on the next.

## Deploying it

`service/src/main/resources/application.properties` now carries what a deployment needs and this
repo can decide — `quarkus.rest.path=/observability/api`,
`quarkus.http.non-application-root-path=/observability/q`, the MCP root-path (without which the
process does not boot at all), the 64M body limit, and the OpenAPI/swagger-ui settings. Read that
file before adding anything here; it explains why each line is load-bearing.

What is still the deployment's to provide:

- allow-list `/observability/api/otel/v1/*` for unauthenticated access. That is the ingest surface,
  and the exporters hitting it are SDKs inside workspace containers, not sessions. In the monorepo
  this lives in `auth/core`'s `PublicPaths`; under the gateway it is `PublicPaths` there.
- **point something at it.** Nothing does today. The overlay that set `OTEL_EXPORTER_OTLP_ENDPOINT`
  on launched services (`OtelEnvironment` in the monorepo) was dropped during the daemon extraction
  as dead code, and the live launch path — the daemon's `ServiceSupervisor` — never had it: the
  `otel:` toggle is parsed, round-tripped through `ConfigJson`, and never acted on. Until that is
  rebuilt beside `ServiceSupervisor` and aimed at this service's address on `qits-net`, this
  receiver has no senders. See `migration-deployables-plan.md` §4a in the superproject.

Routes: `POST /observability/api/otel/v1/{traces,logs,metrics}` (ingest), `GET
/observability/api/telemetry/{errors,slow-spans,logs,metrics}?repositoryId=&workspaceId=` and `GET
/observability/api/telemetry/traces/{traceId}?repositoryId=&workspaceId=` (the UI), plus
`/observability/mcp` (the MCP server, named `observability`) and `/observability/q/{openapi,
swagger-ui}`. Ingest is hidden from the OpenAPI document on purpose — it is a wire protocol spoken
by SDKs, not something a generated client calls.

The repository and the workspace are a **filter**, not a container: this context owns neither, and
buckets by the ids an exporter stamped, so they are query parameters. `{traceId}` is in the path
because it identifies the thing being fetched.

`GET /api/config.json` used to be served here and is now **qits-gateway's**, at that same unchanged
path — it is web-component configuration and the gateway serves the web components.

The tee: when qits itself runs as a managed service the supervising qits injects
`OTEL_EXPORTER_OTLP_ENDPOINT`, and every export is forwarded byte-verbatim upstream *before*
decoding, in addition to being stored locally. Fire-and-forget — an unreachable or rejecting parent
is invisible to the local ingest.

## What is deliberately *not* here

- The **frontend**. The workspace telemetry tab is part of the monorepo's webui, out of scope for
  the whole migration until it becomes per-service Lit components.
- The **`otel` launch toggle** and the env-var injection that points exporters at this receiver:
  that is the service-supervision half, and lives with workspaces / the workspace-daemon.
- **Anything that authenticates.** `PublicPaths` and the auth variants are an open question
  (migration-plan.md §4).
