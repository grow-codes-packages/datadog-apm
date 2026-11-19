package codes.grow.packages.datadogapm;

import codes.grow.packages.datadogapm.tracing.TracingConfig;
import codes.grow.packages.datadogapm.tracing.TracingHandle;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * A running Datadog tracing setup returned by {@link DatadogTracing#initTracing}. It wraps the
 * generic {@link TracingHandle} ({@link #handle()}) and adds the resolved OTLP {@link #endpoint()}.
 */
public final class DatadogTracingHandle implements AutoCloseable {
  private final TracingHandle handle;
  private final String endpoint;

  DatadogTracingHandle(TracingHandle handle, String endpoint) {
    this.handle = handle;
    this.endpoint = endpoint;
  }

  /**
   * The generic tracing handle (for example for the conformance {@code TracingTarget}).
   *
   * @return handle
   */
  public TracingHandle handle() {
    return handle;
  }

  /**
   * The OTLP traces URL spans are exported to.
   *
   * @return endpoint URL
   */
  public String endpoint() {
    return endpoint;
  }

  /**
   * The OpenTelemetry SDK instance.
   *
   * @return SDK
   */
  public OpenTelemetrySdk openTelemetry() {
    return handle.openTelemetry();
  }

  /**
   * The tracer provider.
   *
   * @return tracer provider
   */
  public SdkTracerProvider tracerProvider() {
    return handle.tracerProvider();
  }

  /**
   * The configured propagators ({@code tracecontext}, {@code datadog}, {@code baggage} by default).
   *
   * @return propagators
   */
  public ContextPropagators propagators() {
    return handle.propagators();
  }

  /**
   * The effective tracing config (endpoint, propagators and resource attributes resolved).
   *
   * @return config
   */
  public TracingConfig config() {
    return handle.config();
  }

  /**
   * Returns a tracer.
   *
   * @param name instrumentation scope name
   * @return tracer
   */
  public Tracer tracer(String name) {
    return handle.tracer(name);
  }

  /**
   * Returns a tracer with a scope version.
   *
   * @param name instrumentation scope name
   * @param version instrumentation scope version
   * @return tracer
   */
  public Tracer tracer(String name, String version) {
    return handle.tracer(name, version);
  }

  /** Exports all finished spans, waiting at most the export timeout. */
  public void forceFlush() {
    handle.forceFlush();
  }

  /**
   * Exports all finished spans.
   *
   * @param timeout maximum wait
   */
  public void forceFlush(Duration timeout) {
    handle.forceFlush(timeout);
  }

  /**
   * Asynchronous {@link #forceFlush(Duration)}.
   *
   * @param timeout maximum wait
   * @return completes when flushed
   */
  public CompletableFuture<Void> forceFlushAsync(Duration timeout) {
    return handle.forceFlushAsync(timeout);
  }

  /** Flushes and shuts the tracer provider down; idempotent. */
  public void shutdown() {
    handle.shutdown();
  }

  /**
   * Asynchronous {@link #shutdown()}.
   *
   * @return completes when shut down
   */
  public CompletableFuture<Void> shutdownAsync() {
    return handle.shutdownAsync();
  }

  /**
   * Whether {@link #shutdown()} was called.
   *
   * @return shutdown flag
   */
  public boolean isShutdown() {
    return handle.isShutdown();
  }

  /** Same as {@link #shutdown()}. */
  @Override
  public void close() {
    shutdown();
  }

  @Override
  public String toString() {
    return "DatadogTracingHandle{endpoint=" + endpoint + ", config=" + handle.config() + "}";
  }
}
