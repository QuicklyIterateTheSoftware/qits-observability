package eu.wohlben.qits.domain.telemetry.control;

import eu.wohlben.qits.domain.telemetry.dto.MetricPoint;
import eu.wohlben.qits.domain.telemetry.dto.StoredLog;
import eu.wohlben.qits.domain.telemetry.dto.StoredSpan;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The in-memory telemetry buffer: recent spans, log records and metric points per worktree,
 * bounded, ephemeral — a JVM restart empties it, and that is the feature (no entity, no migration,
 * no H2 table). Same philosophy as the command ring buffer ({@code CommandSession}): byte-accounted
 * deques, evict-oldest.
 *
 * <p>Records are bucketed by the {@code qits.repository.id} / {@code qits.worktree.id} resource
 * attributes that qits stamps into every launch with the {@code otel} toggle ({@code
 * OTEL_RESOURCE_ATTRIBUTES}); telemetry arriving without them lands in a quarantine bucket ({@link
 * #UNSCOPED_KEY}) that is bounded like any other but not exposed by the query surface.
 *
 * <p>Bounding is two-tier: per-worktree count caps (spans/logs/metric series) enforced inside the
 * bucket monitor, and a global byte ceiling enforced afterwards by evicting the oldest records from
 * the <em>fattest</em> bucket — so one chatty daemon pays for its own volume instead of evicting a
 * quieter worktree's telemetry. Lock order is always {@code evictionLock → bucket monitor}, and
 * appenders never take {@code evictionLock} while holding a bucket monitor, so the two tiers can't
 * deadlock.
 */
@ApplicationScoped
public class TelemetryStore {

  private static final Logger LOG = Logger.getLogger(TelemetryStore.class);

  /** Bucket for telemetry that arrived without the qits.* resource attributes. */
  public static final String UNSCOPED_KEY = "_unscoped";

  static final String REPOSITORY_ATTRIBUTE = "qits.repository.id";
  static final String WORKTREE_ATTRIBUTE = "qits.worktree.id";

  // Package-visible so the plain-JUnit store test can shrink them; injected values come from
  // qits.telemetry.* when running in Quarkus (defaults here, pattern of qits.daemons.*).
  @ConfigProperty(name = "qits.telemetry.max-spans-per-worktree", defaultValue = "5000")
  int maxSpansPerWorktree = 5000;

  @ConfigProperty(name = "qits.telemetry.max-logs-per-worktree", defaultValue = "10000")
  int maxLogsPerWorktree = 10000;

  @ConfigProperty(name = "qits.telemetry.max-metric-series-per-worktree", defaultValue = "500")
  int maxMetricSeriesPerWorktree = 500;

  @ConfigProperty(name = "qits.telemetry.max-total-bytes", defaultValue = "67108864")
  long maxTotalBytes = 64L * 1024 * 1024;

  private final ConcurrentHashMap<String, WorktreeBuffer> buffers = new ConcurrentHashMap<>();
  private final AtomicLong totalBytes = new AtomicLong();
  private final Object evictionLock = new Object();

  /** All fields guarded by the buffer's own monitor. */
  private static final class WorktreeBuffer {
    final ArrayDeque<StoredSpan> spans = new ArrayDeque<>();
    final ArrayDeque<StoredLog> logs = new ArrayDeque<>();
    final LinkedHashMap<String, MetricPoint> metrics = new LinkedHashMap<>();
    final HashMap<String, List<StoredSpan>> spansByTrace = new HashMap<>();
    long bytes;
    boolean metricCapWarned;
  }

  public void addSpans(Collection<StoredSpan> spans) {
    for (StoredSpan span : spans) {
      WorktreeBuffer buffer = bufferFor(span.resourceAttributes());
      synchronized (buffer) {
        buffer.spans.addLast(span);
        buffer.spansByTrace.computeIfAbsent(span.traceId(), t -> new ArrayList<>()).add(span);
        account(buffer, TelemetrySizeEstimator.bytesOf(span));
        while (buffer.spans.size() > maxSpansPerWorktree) {
          evictOldestSpan(buffer);
        }
      }
    }
    enforceGlobalCeiling();
  }

  public void addLogs(Collection<StoredLog> logs) {
    for (StoredLog log : logs) {
      WorktreeBuffer buffer = bufferFor(log.resourceAttributes());
      synchronized (buffer) {
        buffer.logs.addLast(log);
        account(buffer, TelemetrySizeEstimator.bytesOf(log));
        while (buffer.logs.size() > maxLogsPerWorktree) {
          account(buffer, -TelemetrySizeEstimator.bytesOf(buffer.logs.removeFirst()));
        }
      }
    }
    enforceGlobalCeiling();
  }

  public void addMetrics(Collection<MetricPoint> points) {
    for (MetricPoint point : points) {
      WorktreeBuffer buffer = bufferFor(point.resourceAttributes());
      synchronized (buffer) {
        String key = point.seriesKey();
        MetricPoint previous = buffer.metrics.get(key);
        if (previous == null && buffer.metrics.size() >= maxMetricSeriesPerWorktree) {
          if (!buffer.metricCapWarned) {
            buffer.metricCapWarned = true;
            LOG.warnf(
                "Telemetry metric-series cap (%d) reached for a worktree; new series are dropped",
                maxMetricSeriesPerWorktree);
          }
          continue;
        }
        buffer.metrics.put(key, point);
        if (previous != null) {
          account(buffer, -TelemetrySizeEstimator.bytesOf(previous));
        }
        account(buffer, TelemetrySizeEstimator.bytesOf(point));
      }
    }
    enforceGlobalCeiling();
  }

  /** Snapshot of the worktree's buffered spans, oldest first. */
  public List<StoredSpan> spans(String repoId, String worktreeId) {
    WorktreeBuffer buffer = buffers.get(key(repoId, worktreeId));
    if (buffer == null) {
      return List.of();
    }
    synchronized (buffer) {
      return List.copyOf(buffer.spans);
    }
  }

  /** The worktree's buffered spans belonging to {@code traceId}, oldest first. */
  public List<StoredSpan> trace(String repoId, String worktreeId, String traceId) {
    WorktreeBuffer buffer = buffers.get(key(repoId, worktreeId));
    if (buffer == null) {
      return List.of();
    }
    synchronized (buffer) {
      List<StoredSpan> spans = buffer.spansByTrace.get(traceId);
      return spans == null ? List.of() : List.copyOf(spans);
    }
  }

  /** Snapshot of the worktree's buffered log records, oldest first. */
  public List<StoredLog> logs(String repoId, String worktreeId) {
    WorktreeBuffer buffer = buffers.get(key(repoId, worktreeId));
    if (buffer == null) {
      return List.of();
    }
    synchronized (buffer) {
      return List.copyOf(buffer.logs);
    }
  }

  /** Snapshot of the worktree's metric series (latest point per series). */
  public List<MetricPoint> metrics(String repoId, String worktreeId) {
    WorktreeBuffer buffer = buffers.get(key(repoId, worktreeId));
    if (buffer == null) {
      return List.of();
    }
    synchronized (buffer) {
      return List.copyOf(buffer.metrics.values());
    }
  }

  /** Total estimated bytes currently retained across all buckets. */
  public long totalBytes() {
    return totalBytes.get();
  }

  /** Drops everything. Test seam (and nothing else calls it). */
  public void clear() {
    synchronized (evictionLock) {
      for (WorktreeBuffer buffer : buffers.values()) {
        synchronized (buffer) {
          totalBytes.addAndGet(-buffer.bytes);
          buffer.bytes = 0;
          buffer.spans.clear();
          buffer.logs.clear();
          buffer.metrics.clear();
          buffer.spansByTrace.clear();
        }
      }
      buffers.clear();
    }
  }

  private WorktreeBuffer bufferFor(Map<String, String> resourceAttributes) {
    String repoId = resourceAttributes.get(REPOSITORY_ATTRIBUTE);
    String worktreeId = resourceAttributes.get(WORKTREE_ATTRIBUTE);
    String key =
        (repoId == null || repoId.isBlank() || worktreeId == null || worktreeId.isBlank())
            ? UNSCOPED_KEY
            : key(repoId, worktreeId);
    return buffers.computeIfAbsent(key, k -> new WorktreeBuffer());
  }

  private static String key(String repoId, String worktreeId) {
    return repoId + "/" + worktreeId;
  }

  /** Caller must hold the buffer monitor. */
  private void account(WorktreeBuffer buffer, int delta) {
    buffer.bytes += delta;
    totalBytes.addAndGet(delta);
  }

  /** Caller must hold the buffer monitor; the buffer must have at least one span. */
  private void evictOldestSpan(WorktreeBuffer buffer) {
    StoredSpan evicted = buffer.spans.removeFirst();
    List<StoredSpan> indexed = buffer.spansByTrace.get(evicted.traceId());
    if (indexed != null) {
      indexed.remove(evicted);
      if (indexed.isEmpty()) {
        buffer.spansByTrace.remove(evicted.traceId());
      }
    }
    account(buffer, -TelemetrySizeEstimator.bytesOf(evicted));
  }

  /**
   * While over the global byte ceiling, evict the oldest span-or-log from whichever bucket
   * currently retains the most bytes. Metrics are never evicted here — they replace in place and
   * are series-capped, so their footprint is already bounded; a bucket holding only metrics is
   * simply skipped.
   */
  private void enforceGlobalCeiling() {
    if (totalBytes.get() <= maxTotalBytes) {
      return;
    }
    synchronized (evictionLock) {
      while (totalBytes.get() > maxTotalBytes) {
        WorktreeBuffer fattest = null;
        long fattestBytes = -1;
        for (WorktreeBuffer buffer : buffers.values()) {
          synchronized (buffer) {
            if (buffer.bytes > fattestBytes
                && (!buffer.spans.isEmpty() || !buffer.logs.isEmpty())) {
              fattest = buffer;
              fattestBytes = buffer.bytes;
            }
          }
        }
        if (fattest == null) {
          return; // nothing evictable (only metrics remain) — give up rather than spin
        }
        synchronized (fattest) {
          StoredSpan oldestSpan = fattest.spans.peekFirst();
          StoredLog oldestLog = fattest.logs.peekFirst();
          if (oldestSpan == null && oldestLog == null) {
            continue; // raced with another evictor; re-pick
          }
          boolean evictSpan =
              oldestLog == null
                  || (oldestSpan != null
                      && oldestSpan.receivedAtMillis() <= oldestLog.receivedAtMillis());
          if (evictSpan) {
            evictOldestSpan(fattest);
          } else {
            account(fattest, -TelemetrySizeEstimator.bytesOf(fattest.logs.removeFirst()));
          }
        }
      }
    }
  }
}
