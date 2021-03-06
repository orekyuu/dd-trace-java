package datadog.trace.common.writer;

import static datadog.trace.api.ConfigDefaults.DEFAULT_AGENT_HOST;
import static datadog.trace.api.ConfigDefaults.DEFAULT_AGENT_TIMEOUT;
import static datadog.trace.api.ConfigDefaults.DEFAULT_AGENT_UNIX_DOMAIN_SOCKET;
import static datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_AGENT_PORT;

import com.timgroup.statsd.NoOpStatsDClient;
import datadog.trace.common.writer.ddagent.DDAgentApi;
import datadog.trace.common.writer.ddagent.DDAgentResponseListener;
import datadog.trace.common.writer.ddagent.TraceProcessingDisruptor;
import datadog.trace.core.DDSpan;
import datadog.trace.core.monitor.Monitor;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;

/**
 * This writer buffers traces and sends them to the provided DDApi instance. Buffering is done with
 * a distruptor to limit blocking the application threads. Internally, the trace is serialized and
 * put onto a separate disruptor that does block to decouple the CPU intensive from the IO bound
 * threads.
 *
 * <p>[Application] -> [trace processing buffer] -> [serialized trace batching buffer] -> [dd-agent]
 *
 * <p>Note: the first buffer is non-blocking and will discard if full, the second is blocking and
 * will cause back pressure on the trace processing (serializing) thread.
 *
 * <p>If the buffer is filled traces are discarded before serializing. Once serialized every effort
 * is made to keep, to avoid wasting the serialization effort.
 */
@Slf4j
public class DDAgentWriter implements Writer {

  private static final int DISRUPTOR_BUFFER_SIZE = 1024;

  private final DDAgentApi api;
  private final TraceProcessingDisruptor traceProcessingDisruptor;

  private final AtomicInteger traceCount = new AtomicInteger(0);
  private volatile boolean closed;

  public final Monitor monitor;

  // Apply defaults to the class generated by lombok.
  public static class DDAgentWriterBuilder {
    String agentHost = DEFAULT_AGENT_HOST;
    int traceAgentPort = DEFAULT_TRACE_AGENT_PORT;
    String unixDomainSocket = DEFAULT_AGENT_UNIX_DOMAIN_SOCKET;
    long timeoutMillis = TimeUnit.SECONDS.toMillis(DEFAULT_AGENT_TIMEOUT);
    int traceBufferSize = DISRUPTOR_BUFFER_SIZE;
    Monitor monitor = new Monitor(new NoOpStatsDClient());
    int flushFrequencySeconds = 1;
  }

  @lombok.Builder
  // These field names must be stable to ensure the builder api is stable.
  private DDAgentWriter(
      final DDAgentApi agentApi,
      final String agentHost,
      final int traceAgentPort,
      final String unixDomainSocket,
      final long timeoutMillis,
      final int traceBufferSize,
      final Monitor monitor,
      final int flushFrequencySeconds) {
    if (agentApi != null) {
      api = agentApi;
    } else {
      api = new DDAgentApi(agentHost, traceAgentPort, unixDomainSocket, timeoutMillis);
    }
    this.monitor = monitor;
    traceProcessingDisruptor =
        new TraceProcessingDisruptor(
            traceBufferSize,
            monitor,
            api,
            flushFrequencySeconds,
            TimeUnit.SECONDS,
            flushFrequencySeconds > 0);
  }

  private DDAgentWriter(
      final DDAgentApi agentApi,
      final Monitor monitor,
      final TraceProcessingDisruptor traceProcessingDisruptor) {
    api = agentApi;
    this.monitor = monitor;
    this.traceProcessingDisruptor = traceProcessingDisruptor;
  }

  public void addResponseListener(final DDAgentResponseListener listener) {
    api.addResponseListener(listener);
  }

  // Exposing some statistics for consumption by monitors
  public final long getDisruptorCapacity() {
    return traceProcessingDisruptor.getDisruptorCapacity();
  }

  public final long getDisruptorUtilizedCapacity() {
    return getDisruptorCapacity() - getDisruptorRemainingCapacity();
  }

  public final long getDisruptorRemainingCapacity() {
    return traceProcessingDisruptor.getDisruptorRemainingCapacity();
  }

  @Override
  public void write(final List<DDSpan> trace) {
    // We can't add events after shutdown otherwise it will never complete shutting down.
    if (!closed) {
      final int representativeCount;
      if (trace.isEmpty() || !(trace.get(0).isRootSpan())) {
        // We don't want to reset the count if we can't correctly report the value.
        representativeCount = 1;
      } else {
        representativeCount = traceCount.getAndSet(0) + 1;
      }
      final boolean published = traceProcessingDisruptor.publish(trace, representativeCount);
      if (published) {
        monitor.onPublish(trace);
      } else {
        // We're discarding the trace, but we still want to count it.
        traceCount.addAndGet(representativeCount);
        log.debug("Trace written to overfilled buffer. Counted but dropping trace: {}", trace);
        monitor.onFailedPublish(trace);
      }
    } else {
      log.debug("Trace written after shutdown. Ignoring trace: {}", trace);
      monitor.onFailedPublish(trace);
    }
  }

  public boolean flush() {
    if (!closed) { // give up after a second
      if (traceProcessingDisruptor.flush(1, TimeUnit.SECONDS)) {
        monitor.onFlush(false);
        return true;
      }
    }
    return false;
  }

  @Override
  public void incrementTraceCount() {
    traceCount.incrementAndGet();
  }

  public DDAgentApi getApi() {
    return api;
  }

  @Override
  public void start() {
    if (!closed) {
      traceProcessingDisruptor.start();
      monitor.onStart((int) getDisruptorCapacity());
    }
  }

  @Override
  public void close() {
    final boolean flushed = flush();
    closed = true;
    traceProcessingDisruptor.close();
    monitor.onShutdown(flushed);
  }
}
