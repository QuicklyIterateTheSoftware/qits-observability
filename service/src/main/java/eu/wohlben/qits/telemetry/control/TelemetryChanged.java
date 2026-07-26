package eu.wohlben.qits.telemetry.control;

/**
 * A payload-free "this workspace's telemetry buffers got new data" signal, fired by {@link
 * TelemetryStore} on every scoped ingest and delivered over CDI async events.
 *
 * <p>This is the extracted, context-local form of the monorepo's {@code
 * WorkspaceChangeHint(repoId, workspaceId, Topic.TELEMETRY)}. Only the {@code TELEMETRY} topic was
 * ever fired from here, so the eleven-value {@code Topic} enum — which names services, commands,
 * bootstrap chains, prompt drafts and agent activity, none of which this context knows about — was
 * not carried across; the topic is implied by the event type instead.
 *
 * <p>A consuming application that also runs the workspaces context bridges the two with a
 * three-line observer:
 *
 * <pre>{@code
 * void onTelemetry(@ObservesAsync TelemetryChanged changed) {
 *   workspaceChangePublisher.fire(changed.repoId(), changed.workspaceId(), Topic.TELEMETRY);
 * }
 * }</pre>
 *
 * <p>With no observer registered the event is a no-op, which is the supported standalone
 * configuration: telemetry still ingests and still answers queries, browsers just do not get a
 * live push and re-read on their own schedule. The hint carries no data by design, so a dropped or
 * missed one self-heals on the next hint or on reconnect.
 */
public record TelemetryChanged(String repoId, String workspaceId) {}
