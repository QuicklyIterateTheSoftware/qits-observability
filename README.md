# qits-observability

The **telemetry** context of qits: an in-process OTLP/HTTP receiver, a bounded in-memory buffer of
what it receives, and a query surface over that buffer for both humans (REST) and coding agents
(MCP). Plus the two managed-app relays that go with it — the upstream OTLP tee and
`/api/config.json`.

    mvn verify        # a clone of this repo alone builds and tests green — no monorepo, no docker

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
| `…/api/` | `OtelReceiverResource` (OTLP ingest), `OtelForwarder` (the upstream tee), `WorkspaceTelemetryController` (the UI's JSON), `ConfigResource` (`/api/config.json`), `TelemetryExceptionMapper`. |
| `…/control/` | `TelemetryDecoder` (protobuf → records), `TelemetryStore` (the buffer), `TelemetryQueryService` (every query both surfaces answer from), `TelemetrySizeEstimator`, `TelemetryChangePublisher`. |
| `…/dto/` | The stored records and the wire DTOs. |
| `…/mcp/` | `TelemetryMcpTools` (five tools on the `repository` MCP server), `TelemetryToolFilter`, `RepositoryScope`, `WorkspaceScope`, and the two ports. |
| `…/error/` | This context's own `DomainException` family (migration-plan.md §5). |

One module, not the usual `domain/` + `service/` pair. This is the only qits context whose business
logic already lived entirely in the monorepo's `service` module (migration-plan.md §3.6) — there is
no `domain/telemetry` to replay. The directory is still called `service/` because the replayed git
history is anchored to `service/src/**`.

A library jar, not an app: a consuming Quarkus application pulls it in and gets the receiver, the
routes and the tools.

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

A standalone deployable must:

- set `quarkus.rest.path=/api` — every route below assumes it;
- set `quarkus.mcp.server.repository.http.root-path=/mcp/repository` if it wants the MCP tools;
- allow-list `/api/otel/v1/*` for unauthenticated access. That is the ingest surface, and the
  exporters hitting it are SDKs inside workspace containers, not sessions. In the monorepo this
  lives in `auth/core`'s `PublicPaths`. Note the wire-body limit gates it too
  (`quarkus.http.limits.max-body-size`).

Routes: `POST /api/otel/v1/{traces,logs,metrics}` (ingest), `GET
/api/repositories/{repoId}/workspaces/{workspaceId}/telemetry/{errors,traces/{traceId},slow-spans,logs,metrics}`
(the UI), `GET /api/config.json` (the managed-app relay). The last two are hidden from the OpenAPI
document on purpose — `/api/otel/*` is a wire protocol and `config.json` is fetched pre-bootstrap by
`@qits/angular`, so neither belongs in the generated client.

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
