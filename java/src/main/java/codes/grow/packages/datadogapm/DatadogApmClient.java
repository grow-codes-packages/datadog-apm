package codes.grow.packages.datadogapm;

import codes.grow.packages.datadogapm.core.CancelledException;
import codes.grow.packages.datadogapm.core.ConfigException;
import codes.grow.packages.datadogapm.core.DatadogApmException;
import codes.grow.packages.datadogapm.core.EnvReader;
import codes.grow.packages.datadogapm.core.HealthStatus;
import codes.grow.packages.datadogapm.core.Hooks;
import codes.grow.packages.datadogapm.core.HttpTransport;
import codes.grow.packages.datadogapm.core.Json;
import codes.grow.packages.datadogapm.core.Logger;
import codes.grow.packages.datadogapm.core.RetryPolicy;
import codes.grow.packages.datadogapm.core.Secret;
import codes.grow.packages.datadogapm.core.TlsOptions;
import codes.grow.packages.datadogapm.core.TransportRequest;
import codes.grow.packages.datadogapm.core.TransportResponse;
import codes.grow.packages.datadogapm.core.UnavailableException;
import codes.grow.packages.datadogapm.core.UnsupportedException;
import codes.grow.packages.datadogapm.core.ValidationException;
import codes.grow.packages.datadogapm.tracing.TracingConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The Datadog APM client (API.md section 1). {@link #health()}, {@link #ping()} and {@link
 * #serverVersion()} use the local Datadog Agent's {@code GET /info} (trace intake port 8126, no
 * authentication); {@link #initTracing(TracingConfig)} sets up OpenTelemetry exporting to the
 * Agent; {@link #spans()} is the SaaS query API (needs {@code apiKey} + {@code appKey}).
 * Thread-safe.
 *
 * <pre>{@code
 * try (DatadogApmClient client = DatadogApmClient.fromEnv()) {
 *   DatadogTracingHandle tracing = client.initTracing(TracingConfig.builder("checkout").build());
 *   String agent = client.serverVersion(); // "7.85.4"
 * } // close() flushes and shuts down the tracing it started
 * }</pre>
 *
 * <p>Every I/O method has a blocking form and an {@code ...Async} form returning {@link
 * CompletableFuture}.
 */
public final class DatadogApmClient implements AutoCloseable {
  private final DatadogApmConfig config;
  private final HttpTransport transport;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final List<AutoCloseable> resources = new ArrayList<>();
  private DatadogSpansQuery spans;

  private DatadogApmClient(DatadogApmConfig config) {
    this.config = config;
    this.transport = new HttpTransport(config.toTransportConfig());
  }

  /**
   * Creates a client.
   *
   * @param config validated configuration
   * @return a new client
   * @throws ConfigException when the configuration is invalid
   */
  public static DatadogApmClient create(DatadogApmConfig config) {
    config.validate();
    return new DatadogApmClient(config);
  }

  /**
   * Creates a client from {@code GROW_DATADOG_APM_*} and {@code DD_*} variables (see {@link
   * DatadogApmConfig}).
   *
   * @return a new client
   */
  public static DatadogApmClient fromEnv() {
    return create(DatadogApmConfig.fromEnv());
  }

  /**
   * Starts a client builder.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Returns the configuration.
   *
   * @return the configuration
   */
  public DatadogApmConfig config() {
    return config;
  }

  /**
   * Returns the HTTP transport to the Agent.
   *
   * @return the transport
   */
  public HttpTransport transport() {
    return transport;
  }

  /**
   * Registers a resource closed with the client.
   *
   * @param resource the resource
   */
  public void register(AutoCloseable resource) {
    synchronized (resources) {
      resources.add(resource);
    }
  }

  /**
   * The SaaS spans query API (created on first use, closed by {@link #close()}).
   *
   * @return query client
   * @throws ConfigException when {@code apiKey} or {@code appKey} is missing
   */
  public synchronized DatadogSpansQuery spans() {
    if (spans == null) {
      spans = DatadogSpansQuery.create(config);
      register(spans);
    }
    return spans;
  }

  private static TransportRequest.Builder info(String operation) {
    return TransportRequest.get("/info").operation(operation);
  }

  private static AgentInfo parseInfo(TransportResponse r) {
    JsonNode doc;
    if (r.body().length == 0) {
      doc = MissingNode.getInstance();
    } else {
      try {
        doc = Json.tree(r.body());
      } catch (ValidationException e) {
        throw new UnavailableException(
            "agentInfo: invalid JSON response", r.status(), r.requestId().orElse(null), null, e);
      }
    }
    List<String> endpoints = new ArrayList<>();
    for (JsonNode e : doc.path("endpoints")) {
      if (e.isTextual()) {
        endpoints.add(e.asText());
      }
    }
    return new AgentInfo(
        doc.path("version").asText(""),
        doc.path("git_commit").asText(""),
        endpoints,
        doc.path("config"),
        doc);
  }

  /**
   * Returns the Agent's {@code GET /info}.
   *
   * @return the document
   */
  public AgentInfo agentInfo() {
    return HttpTransport.await(agentInfoAsync(), "agentInfo");
  }

  /**
   * Asynchronous {@link #agentInfo()}.
   *
   * @return a future with the document
   */
  public CompletableFuture<AgentInfo> agentInfoAsync() {
    return transport.sendAsync(info("agentInfo").build()).thenApply(DatadogApmClient::parseInfo);
  }

  /**
   * Checks Agent liveness with {@code GET /info} (one attempt): healthy with status {@code ok} and
   * details {@code version}, {@code git_commit} when the Agent answers; {@code unhealthy} (details
   * {@code httpStatus}, {@code error}) on HTTP errors; {@code unreachable} (details {@code error})
   * on network errors. Never throws for an unhealthy or unreachable Agent.
   *
   * @return the health status
   * @throws CancelledException when interrupted or closed
   */
  public HealthStatus health() {
    return HttpTransport.await(healthAsync(), "health");
  }

  /**
   * Asynchronous {@link #health()}.
   *
   * @return a future with the health status
   */
  public CompletableFuture<HealthStatus> healthAsync() {
    CompletableFuture<TransportResponse> sent;
    try {
      sent = transport.sendAsync(info("health").maxRetries(0).build());
    } catch (RuntimeException e) {
      sent = CompletableFuture.failedFuture(e);
    }
    return sent.handle(
        (r, err) -> {
          if (err == null) {
            try {
              AgentInfo info = parseInfo(r);
              Map<String, String> details = new LinkedHashMap<>();
              if (!info.version().isEmpty()) {
                details.put("version", info.version());
              }
              if (!info.gitCommit().isEmpty()) {
                details.put("git_commit", info.gitCommit());
              }
              return new HealthStatus(true, "ok", details);
            } catch (DatadogApmException e) {
              err = e;
            }
          }
          DatadogApmException e = HttpTransport.asSdkException(err, "health");
          if (e instanceof CancelledException || e instanceof ConfigException) {
            throw e;
          }
          if (e.httpStatus().isPresent()) {
            return new HealthStatus(
                false,
                "unhealthy",
                Map.of(
                    "httpStatus",
                    Integer.toString(e.httpStatus().getAsInt()),
                    "error",
                    e.code().wireValue()));
          }
          return new HealthStatus(false, "unreachable", Map.of("error", e.code().wireValue()));
        });
  }

  /**
   * Round trip to the Agent ({@code GET /info}) and its latency. The Agent has no authentication;
   * to check Datadog API credentials use {@code spans().ping()}.
   *
   * @return the latency
   */
  public Duration ping() {
    return HttpTransport.await(pingAsync(), "ping");
  }

  /**
   * Asynchronous {@link #ping()}.
   *
   * @return a future with the latency
   */
  public CompletableFuture<Duration> pingAsync() {
    long start = System.nanoTime();
    return transport
        .sendAsync(info("ping").build())
        .thenApply(r -> Duration.ofNanos(System.nanoTime() - start));
  }

  /**
   * The Datadog Agent version from {@code GET /info} (e.g. {@code 7.85.4}).
   *
   * @return the version string
   * @throws UnsupportedException when the Agent reports none
   */
  public String serverVersion() {
    return HttpTransport.await(serverVersionAsync(), "serverVersion");
  }

  /**
   * Asynchronous {@link #serverVersion()}.
   *
   * @return a future with the version
   */
  public CompletableFuture<String> serverVersionAsync() {
    return agentInfoAsync()
        .thenApply(
            info -> {
              if (info.version().isEmpty()) {
                throw new UnsupportedException(
                    "serverVersion: the Datadog Agent did not report a version");
              }
              return info.version();
            });
  }

  /**
   * {@link DatadogTracing#initTracing(TracingConfig, DatadogApmConfig)} with this client's
   * configuration as the defaults (Agent host, OTLP endpoint, service version, env, tags, TLS).
   * {@link #close()} shuts the handle down.
   *
   * @param tracing tracing config
   * @return handle
   */
  public DatadogTracingHandle initTracing(TracingConfig tracing) {
    DatadogTracingHandle h = DatadogTracing.initTracing(tracing, config);
    register(h);
    return h;
  }

  /**
   * {@link #initTracing(TracingConfig)} with every tracing setting from this client's configuration
   * ({@link DatadogTracing#tracingConfig(DatadogApmConfig)}).
   *
   * @return handle
   * @throws ConfigException when the configuration has no service name
   */
  public DatadogTracingHandle initTracing() {
    return initTracing(DatadogTracing.tracingConfig(config).build());
  }

  /**
   * Returns whether {@link #close()} was called.
   *
   * @return true once closed
   */
  public boolean isClosed() {
    return closed.get();
  }

  /**
   * Flushes and shuts down every tracing handle started with {@link #initTracing}, closes the query
   * API and releases connections. Idempotent; later calls raise {@link CancelledException}.
   */
  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    List<AutoCloseable> copy;
    synchronized (resources) {
      copy = new ArrayList<>(resources);
      resources.clear();
    }
    for (AutoCloseable r : copy) {
      try {
        r.close();
      } catch (Exception ignored) {
        // best effort: close must not throw
      }
    }
    transport.close();
  }

  @Override
  public String toString() {
    return "DatadogApmClient{" + config + "}";
  }

  /** Builder that configures and creates a client in one chain. */
  public static final class Builder {
    private final DatadogApmConfig.Builder config = DatadogApmConfig.builder();

    private Builder() {}

    /**
     * Fills unset fields from an environment reader.
     *
     * @param env reader, e.g. {@link EnvReader#system()}
     * @return this builder
     */
    public Builder fromEnv(EnvReader env) {
      config.fromEnv(env);
      return this;
    }

    /**
     * Sets the Agent trace intake URL.
     *
     * @param url URL
     * @return this builder
     */
    public Builder url(String url) {
      config.url(url);
      return this;
    }

    /**
     * Sets the OTLP endpoint of the Agent.
     *
     * @param endpoint URL
     * @return this builder
     */
    public Builder otlpEndpoint(String endpoint) {
      config.otlpEndpoint(endpoint);
      return this;
    }

    /**
     * Sets the OTLP protocol.
     *
     * @param protocol protocol
     * @return this builder
     */
    public Builder protocol(TracingConfig.Protocol protocol) {
      config.protocol(protocol);
      return this;
    }

    /**
     * Sets the service name.
     *
     * @param serviceName name
     * @return this builder
     */
    public Builder serviceName(String serviceName) {
      config.serviceName(serviceName);
      return this;
    }

    /**
     * Sets the service version.
     *
     * @param serviceVersion version
     * @return this builder
     */
    public Builder serviceVersion(String serviceVersion) {
      config.serviceVersion(serviceVersion);
      return this;
    }

    /**
     * Sets the environment.
     *
     * @param environment environment
     * @return this builder
     */
    public Builder environment(String environment) {
      config.environment(environment);
      return this;
    }

    /**
     * Adds a tag (extra resource attribute).
     *
     * @param key key
     * @param value value
     * @return this builder
     */
    public Builder tag(String key, String value) {
      config.tag(key, value);
      return this;
    }

    /**
     * Sets the Datadog API key.
     *
     * @param apiKey key
     * @return this builder
     */
    public Builder apiKey(Secret apiKey) {
      config.apiKey(apiKey);
      return this;
    }

    /**
     * Sets the Datadog API key.
     *
     * @param apiKey key
     * @return this builder
     */
    public Builder apiKey(String apiKey) {
      config.apiKey(apiKey);
      return this;
    }

    /**
     * Sets the Datadog application key.
     *
     * @param appKey key
     * @return this builder
     */
    public Builder appKey(Secret appKey) {
      config.appKey(appKey);
      return this;
    }

    /**
     * Sets the Datadog application key.
     *
     * @param appKey key
     * @return this builder
     */
    public Builder appKey(String appKey) {
      config.appKey(appKey);
      return this;
    }

    /**
     * Sets the Datadog site.
     *
     * @param site site
     * @return this builder
     */
    public Builder site(String site) {
      config.site(site);
      return this;
    }

    /**
     * Sets the query API base URL.
     *
     * @param apiUrl URL
     * @return this builder
     */
    public Builder apiUrl(String apiUrl) {
      config.apiUrl(apiUrl);
      return this;
    }

    /**
     * Sets the connect timeout.
     *
     * @param d duration
     * @return this builder
     */
    public Builder connectTimeout(Duration d) {
      config.connectTimeout(d);
      return this;
    }

    /**
     * Sets the per-attempt timeout.
     *
     * @param d duration
     * @return this builder
     */
    public Builder requestTimeout(Duration d) {
      config.requestTimeout(d);
      return this;
    }

    /**
     * Sets the total timeout.
     *
     * @param d duration
     * @return this builder
     */
    public Builder totalTimeout(Duration d) {
      config.totalTimeout(d);
      return this;
    }

    /**
     * Sets the retry policy.
     *
     * @param retry policy
     * @return this builder
     */
    public Builder retry(RetryPolicy retry) {
      config.retry(retry);
      return this;
    }

    /**
     * Sets the TLS options.
     *
     * @param tls options
     * @return this builder
     */
    public Builder tls(TlsOptions tls) {
      config.tls(tls);
      return this;
    }

    /**
     * Sets the proxy URL.
     *
     * @param proxy URL
     * @return this builder
     */
    public Builder proxy(String proxy) {
      config.proxy(proxy);
      return this;
    }

    /**
     * Adds a header.
     *
     * @param name name
     * @param value value
     * @return this builder
     */
    public Builder header(String name, String value) {
      config.header(name, value);
      return this;
    }

    /**
     * Sets the user agent suffix.
     *
     * @param suffix suffix
     * @return this builder
     */
    public Builder userAgentSuffix(String suffix) {
      config.userAgentSuffix(suffix);
      return this;
    }

    /**
     * Sets hooks.
     *
     * @param hooks hooks
     * @return this builder
     */
    public Builder hooks(Hooks hooks) {
      config.hooks(hooks);
      return this;
    }

    /**
     * Sets the logger.
     *
     * @param logger logger
     * @return this builder
     */
    public Builder logger(Logger logger) {
      config.logger(logger);
      return this;
    }

    /**
     * Builds the configuration and the client.
     *
     * @return a new client
     * @throws ConfigException when the configuration is invalid
     */
    public DatadogApmClient build() {
      return create(config.build());
    }
  }
}
