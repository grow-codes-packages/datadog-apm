package codes.grow.packages.datadogapm;

import codes.grow.packages.datadogapm.core.ConfigException;
import codes.grow.packages.datadogapm.core.EnvReader;
import codes.grow.packages.datadogapm.core.Hooks;
import codes.grow.packages.datadogapm.core.Logger;
import codes.grow.packages.datadogapm.core.Redaction;
import codes.grow.packages.datadogapm.core.RetryPolicy;
import codes.grow.packages.datadogapm.core.Secret;
import codes.grow.packages.datadogapm.core.TlsOptions;
import codes.grow.packages.datadogapm.core.TransportConfig;
import codes.grow.packages.datadogapm.tracing.TracingConfig;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Immutable configuration of a {@link DatadogApmClient}, {@link DatadogTracing#initTracing} and
 * {@link DatadogSpansQuery} (SPEC section 2.2, API.md section 0, DECISIONS.md #1).
 *
 * <p>Build one with {@link #builder()} or read it from the environment with {@link #fromEnv()}.
 * Precedence: explicit builder value &gt; {@code GROW_DATADOG_APM_<NAME>} &gt; vendor variables
 * &gt; default. {@link #toString()} never prints credentials.
 *
 * <table>
 *   <caption>Environment variables</caption>
 *   <tr><th>Field</th><th>GROW_DATADOG_APM_*</th><th>Vendor variables</th></tr>
 *   <tr><td>url</td><td>URL</td><td>DD_TRACE_AGENT_URL, else
 *       http://${DD_AGENT_HOST}:${DD_TRACE_AGENT_PORT}</td></tr>
 *   <tr><td>otlpEndpoint</td><td>OTLP_HTTP_ENDPOINT / OTLP_GRPC_ENDPOINT (by protocol),
 *       OTLP_ENDPOINT</td><td>OTEL_EXPORTER_OTLP_TRACES_ENDPOINT, OTEL_EXPORTER_OTLP_ENDPOINT</td></tr>
 *   <tr><td>protocol</td><td>OTLP_PROTOCOL</td><td>OTEL_EXPORTER_OTLP_TRACES_PROTOCOL,
 *       OTEL_EXPORTER_OTLP_PROTOCOL</td></tr>
 *   <tr><td>serviceName</td><td>SERVICE</td><td>DD_SERVICE, OTEL_SERVICE_NAME</td></tr>
 *   <tr><td>serviceVersion</td><td>VERSION</td><td>DD_VERSION</td></tr>
 *   <tr><td>environment</td><td>ENV</td><td>DD_ENV</td></tr>
 *   <tr><td>tags</td><td>TAGS</td><td>DD_TAGS (explicit tags are merged over them)</td></tr>
 *   <tr><td>apiKey</td><td>API_KEY</td><td>DD_API_KEY</td></tr>
 *   <tr><td>appKey</td><td>APP_KEY</td><td>DD_APP_KEY, DD_APPLICATION_KEY</td></tr>
 *   <tr><td>site</td><td>SITE</td><td>DD_SITE</td></tr>
 *   <tr><td>apiUrl</td><td>API_URL</td><td>-</td></tr>
 * </table>
 */
public final class DatadogApmConfig {
  /** Default Agent OTLP/HTTP port. */
  public static final int OTLP_HTTP_PORT = 4318;

  /** Default Agent OTLP/gRPC port. */
  public static final int OTLP_GRPC_PORT = 4317;

  /** Default Agent trace intake port (serves {@code GET /info}). */
  public static final int AGENT_TRACE_PORT = 8126;

  /** Default Datadog Agent trace intake URL (health, ping and serverVersion use its /info). */
  public static final String DEFAULT_URL = "http://127.0.0.1:8126";

  /** Default OTLP protocol of this package. */
  public static final TracingConfig.Protocol DEFAULT_PROTOCOL =
      TracingConfig.Protocol.HTTP_PROTOBUF;

  static final String[] URL_VENDOR_VARS = {"DD_TRACE_AGENT_URL"};
  static final String[] SERVICE_VENDOR_VARS = {"DD_SERVICE", "OTEL_SERVICE_NAME"};
  static final String[] VERSION_VENDOR_VARS = {"DD_VERSION"};
  static final String[] ENV_VENDOR_VARS = {"DD_ENV"};
  static final String[] TAGS_VENDOR_VARS = {"DD_TAGS"};
  static final String[] API_KEY_VENDOR_VARS = {"DD_API_KEY"};
  static final String[] APP_KEY_VENDOR_VARS = {"DD_APP_KEY", "DD_APPLICATION_KEY"};
  static final String[] SITE_VENDOR_VARS = {"DD_SITE"};
  static final String[] PROTOCOL_VENDOR_VARS = {
    "OTEL_EXPORTER_OTLP_TRACES_PROTOCOL", "OTEL_EXPORTER_OTLP_PROTOCOL"
  };

  private final String url;
  private final String otlpEndpoint;
  private final TracingConfig.Protocol protocol;
  private final String serviceName;
  private final String serviceVersion;
  private final String environment;
  private final Map<String, String> tags;
  private final Secret apiKey;
  private final Secret appKey;
  private final String site;
  private final String apiUrl;
  private final Duration connectTimeout;
  private final Duration requestTimeout;
  private final Duration totalTimeout;
  private final RetryPolicy retry;
  private final TlsOptions tls;
  private final String proxy;
  private final Map<String, String> headers;
  private final String userAgentSuffix;
  private final Hooks hooks;
  private final Logger logger;

  private DatadogApmConfig(Builder b) {
    this.url = b.url != null ? b.url : DEFAULT_URL;
    this.otlpEndpoint = b.otlpEndpoint;
    this.protocol = b.protocol != null ? b.protocol : DEFAULT_PROTOCOL;
    this.serviceName = b.serviceName;
    this.serviceVersion = b.serviceVersion;
    this.environment = b.environment;
    this.tags = Map.copyOf(b.tags);
    this.apiKey = b.apiKey;
    this.appKey = b.appKey;
    this.site =
        b.site == null || b.site.isBlank()
            ? DatadogSites.DEFAULT_SITE
            : DatadogSites.normalizeSite(b.site);
    this.apiUrl = b.apiUrl != null ? b.apiUrl : "https://api." + this.site;
    this.connectTimeout = b.connectTimeout != null ? b.connectTimeout : Duration.ofSeconds(10);
    this.requestTimeout = b.requestTimeout != null ? b.requestTimeout : Duration.ofSeconds(30);
    this.totalTimeout = b.totalTimeout != null ? b.totalTimeout : Duration.ofSeconds(120);
    this.retry = b.retry != null ? b.retry : RetryPolicy.defaults();
    this.tls = b.tls != null ? b.tls : TlsOptions.defaults();
    this.proxy = b.proxy;
    this.headers = Map.copyOf(b.headers);
    this.userAgentSuffix = b.userAgentSuffix;
    this.hooks = b.hooks != null ? b.hooks : Hooks.NONE;
    this.logger = b.logger != null ? b.logger : Logger.NOOP;
  }

  /**
   * Starts an empty builder (every field at its default).
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Returns a builder with these values.
   *
   * @return builder copy
   */
  public Builder toBuilder() {
    Builder b = new Builder();
    b.url = url;
    b.otlpEndpoint = otlpEndpoint;
    b.protocol = protocol;
    b.serviceName = serviceName;
    b.serviceVersion = serviceVersion;
    b.environment = environment;
    b.tags.putAll(tags);
    b.apiKey = apiKey;
    b.appKey = appKey;
    b.site = site;
    b.apiUrl = apiUrl;
    b.connectTimeout = connectTimeout;
    b.requestTimeout = requestTimeout;
    b.totalTimeout = totalTimeout;
    b.retry = retry;
    b.tls = tls;
    b.proxy = proxy;
    b.headers.putAll(headers);
    b.userAgentSuffix = userAgentSuffix;
    b.hooks = hooks;
    b.logger = logger;
    return b;
  }

  /**
   * Reads the configuration from the process environment.
   *
   * @return the validated configuration
   * @throws ConfigException when a variable holds an invalid value (the message names the variable,
   *     never the value)
   */
  public static DatadogApmConfig fromEnv() {
    return builder().fromEnv(EnvReader.system()).build();
  }

  /**
   * Reads the configuration from an explicit variable map (tests).
   *
   * @param env variable map
   * @return the validated configuration
   */
  public static DatadogApmConfig fromEnv(Map<String, String> env) {
    return builder().fromEnv(EnvReader.of(env)).build();
  }

  /**
   * Parses Datadog tags ({@code DD_TAGS}): {@code key:value} pairs separated by commas and/or
   * spaces. Entries without a {@code :} or with an empty key are ignored.
   *
   * <pre>{@code
   * DatadogApmConfig.parseDatadogTags("team:sdk, tier:web"); // {team=sdk, tier=web}
   * }</pre>
   *
   * @param raw tag text
   * @return tags in input order
   */
  public static Map<String, String> parseDatadogTags(String raw) {
    Map<String, String> out = new LinkedHashMap<>();
    if (raw == null) {
      return out;
    }
    for (String part : raw.split("[\\s,]+")) {
      int colon = part.indexOf(':');
      if (colon <= 0) {
        continue;
      }
      out.put(part.substring(0, colon), part.substring(colon + 1));
    }
    return out;
  }

  private static boolean hasPath(String value) {
    try {
      String p = new URI(value).getPath();
      return p != null && !p.isEmpty() && !"/".equals(p);
    } catch (URISyntaxException e) {
      return false;
    }
  }

  private static boolean isHttpUrl(String value) {
    try {
      URI u = new URI(value);
      String s = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
      return (s.equals("http") || s.equals("https")) && u.getHost() != null;
    } catch (URISyntaxException e) {
      return false;
    }
  }

  /**
   * The exporter URL for a protocol and an OTLP endpoint: for {@code http/protobuf} an endpoint
   * without a path gets {@code /v1/traces}; gRPC endpoints are used as they are.
   *
   * @param protocol OTLP protocol
   * @param endpoint endpoint URL
   * @return the traces URL
   */
  public static String otlpTracesUrl(TracingConfig.Protocol protocol, String endpoint) {
    if (protocol == TracingConfig.Protocol.GRPC || hasPath(endpoint)) {
      return endpoint;
    }
    return endpoint.replaceAll("/+$", "") + "/v1/traces";
  }

  /**
   * The resolved OTLP traces URL for {@link #protocol()} (see {@link #otlpTracesUrl}): {@link
   * #otlpEndpoint()} when set, else the host of {@link #url()} with port 4318 + {@code /v1/traces}
   * (HTTP) or port 4317 (gRPC).
   *
   * @return the traces URL
   */
  public String otlpTracesEndpoint() {
    if (otlpEndpoint != null) {
      return otlpTracesUrl(protocol, otlpEndpoint);
    }
    int port = protocol == TracingConfig.Protocol.GRPC ? OTLP_GRPC_PORT : OTLP_HTTP_PORT;
    String scheme = "http";
    String host = "127.0.0.1";
    try {
      URI u = new URI(url);
      if (u.getHost() != null) {
        scheme = u.getScheme();
        host = u.getHost();
      }
    } catch (URISyntaxException e) {
      // validate() rejects such URLs; keep the local default
    }
    return otlpTracesUrl(protocol, scheme + "://" + host + ":" + port);
  }

  /**
   * Whether both the API key and the application key are set (the query API is usable).
   *
   * @return true when {@link DatadogSpansQuery} can be created
   */
  public boolean hasQueryCredentials() {
    return apiKey != null && appKey != null;
  }

  /**
   * The credential values to redact from errors and logs.
   *
   * @return secret values
   */
  public List<String> knownSecrets() {
    List<String> out = new ArrayList<>();
    for (Secret s : new Secret[] {apiKey, appKey}) {
      if (s != null && !s.isCleared()) {
        out.add(s.reveal());
      }
    }
    return out;
  }

  /**
   * Checks every field.
   *
   * @throws ConfigException when a rule is violated
   */
  public void validate() {
    if (!isHttpUrl(url)) {
      throw new ConfigException("config: url must use http or https and include a host");
    }
    requirePositive("connectTimeout", connectTimeout);
    requirePositive("requestTimeout", requestTimeout);
    requirePositive("totalTimeout", totalTimeout);
    tls.validate();
    if (proxy != null) {
      try {
        if (new URI(proxy).getHost() == null) {
          throw new ConfigException("config: proxy must be an absolute URL");
        }
      } catch (URISyntaxException e) {
        throw new ConfigException("config: proxy is not a valid URL");
      }
    }
    List<String> problems = new ArrayList<>();
    if (protocol == TracingConfig.Protocol.NATIVE) {
      problems.add("protocol must be http/protobuf or grpc");
    }
    if (otlpEndpoint != null && !isHttpUrl(otlpEndpoint)) {
      problems.add("otlpEndpoint must be an http or https URL");
    }
    if (!isHttpUrl(apiUrl)) {
      problems.add("apiUrl must be an http or https URL");
    }
    blank(problems, "serviceName", serviceName);
    blank(problems, "serviceVersion", serviceVersion);
    blank(problems, "environment", environment);
    if (apiKey != null && apiKey.reveal().isBlank()) {
      problems.add("apiKey must not be blank");
    }
    if (appKey != null && appKey.reveal().isBlank()) {
      problems.add("appKey must not be blank");
    }
    if (!problems.isEmpty()) {
      throw new ConfigException("config: " + String.join("; ", problems));
    }
    DatadogSites.validateSite(site);
  }

  private static void blank(List<String> problems, String name, String value) {
    if (value != null && value.isBlank()) {
      problems.add(name + " must not be blank");
    }
  }

  private static void requirePositive(String name, Duration d) {
    if (d.isZero() || d.isNegative()) {
      throw new ConfigException("config: " + name + " must be positive");
    }
  }

  /**
   * Transport settings for the Agent ({@link #url()}).
   *
   * @return the transport configuration
   */
  public TransportConfig toTransportConfig() {
    return transportConfig(url);
  }

  /**
   * Transport settings for the query API ({@link #apiUrl()}).
   *
   * @return the transport configuration
   */
  public TransportConfig queryTransportConfig() {
    return transportConfig(apiUrl);
  }

  private TransportConfig transportConfig(String base) {
    return TransportConfig.builder(base)
        .connectTimeout(connectTimeout)
        .requestTimeout(requestTimeout)
        .totalTimeout(totalTimeout)
        .retry(retry)
        .tls(tls)
        .proxy(proxy)
        .headers(headers)
        .userAgentSuffix(userAgentSuffix)
        .hooks(hooks)
        .logger(logger)
        .knownSecrets(this::knownSecrets)
        .build();
  }

  /**
   * Agent trace intake base URL ({@code GET /info}).
   *
   * @return the URL
   */
  public String url() {
    return url;
  }

  /**
   * OTLP endpoint as configured (see {@link #otlpTracesEndpoint()} for the resolved URL).
   *
   * @return the endpoint, or empty
   */
  public Optional<String> otlpEndpoint() {
    return Optional.ofNullable(otlpEndpoint);
  }

  /**
   * OTLP protocol ({@code http/protobuf} by default, or {@code grpc}).
   *
   * @return the protocol
   */
  public TracingConfig.Protocol protocol() {
    return protocol;
  }

  /**
   * {@code service} of unified service tagging (resource {@code service.name}).
   *
   * @return the service, or empty
   */
  public Optional<String> serviceName() {
    return Optional.ofNullable(serviceName);
  }

  /**
   * {@code version} of unified service tagging (resource {@code service.version}).
   *
   * @return the version, or empty
   */
  public Optional<String> serviceVersion() {
    return Optional.ofNullable(serviceVersion);
  }

  /**
   * {@code env} of unified service tagging (resource {@code deployment.environment.name}).
   *
   * @return the environment, or empty
   */
  public Optional<String> environment() {
    return Optional.ofNullable(environment);
  }

  /**
   * Extra resource attributes ({@code DD_TAGS}).
   *
   * @return immutable map
   */
  public Map<String, String> tags() {
    return tags;
  }

  /**
   * Datadog API key (query API only).
   *
   * @return the key, or empty
   */
  public Optional<Secret> apiKey() {
    return Optional.ofNullable(apiKey);
  }

  /**
   * Datadog application key with {@code apm_read} (query API only).
   *
   * @return the key, or empty
   */
  public Optional<Secret> appKey() {
    return Optional.ofNullable(appKey);
  }

  /**
   * Datadog site (default {@code datadoghq.com}).
   *
   * @return the site
   */
  public String site() {
    return site;
  }

  /**
   * Query API base URL (default {@code https://api.<site>}).
   *
   * @return the URL
   */
  public String apiUrl() {
    return apiUrl;
  }

  /**
   * Returns the connect timeout.
   *
   * @return the timeout
   */
  public Duration connectTimeout() {
    return connectTimeout;
  }

  /**
   * Returns the per-attempt timeout.
   *
   * @return the timeout
   */
  public Duration requestTimeout() {
    return requestTimeout;
  }

  /**
   * Returns the total timeout per call including retries.
   *
   * @return the timeout
   */
  public Duration totalTimeout() {
    return totalTimeout;
  }

  /**
   * Returns the retry policy.
   *
   * @return the policy
   */
  public RetryPolicy retry() {
    return retry;
  }

  /**
   * Returns the TLS options.
   *
   * @return the options
   */
  public TlsOptions tls() {
    return tls;
  }

  /**
   * Returns the explicit proxy URL.
   *
   * @return the proxy, or empty
   */
  public Optional<String> proxy() {
    return Optional.ofNullable(proxy);
  }

  /**
   * Returns the extra request headers.
   *
   * @return immutable map
   */
  public Map<String, String> headers() {
    return headers;
  }

  /**
   * Returns the user agent suffix.
   *
   * @return the suffix, or empty
   */
  public Optional<String> userAgentSuffix() {
    return Optional.ofNullable(userAgentSuffix);
  }

  /**
   * Returns the hooks.
   *
   * @return the hooks
   */
  public Hooks hooks() {
    return hooks;
  }

  /**
   * Returns the logger.
   *
   * @return the logger
   */
  public Logger logger() {
    return logger;
  }

  @Override
  public String toString() {
    return "DatadogApmConfig{url="
        + Redaction.redactUrl(url)
        + ", protocol="
        + protocol.wireValue()
        + ", otlpEndpoint="
        + Redaction.redactUrl(otlpTracesEndpoint())
        + ", serviceName="
        + serviceName
        + ", serviceVersion="
        + serviceVersion
        + ", environment="
        + environment
        + ", tags="
        + new TreeMap<>(tags)
        + ", site="
        + site
        + ", apiUrl="
        + Redaction.redactUrl(apiUrl)
        + ", apiKey="
        + (apiKey == null ? "null" : Redaction.REDACTED)
        + ", appKey="
        + (appKey == null ? "null" : Redaction.REDACTED)
        + ", connectTimeout="
        + connectTimeout
        + ", requestTimeout="
        + requestTimeout
        + ", totalTimeout="
        + totalTimeout
        + ", retry="
        + retry
        + ", tls="
        + tls
        + ", proxy="
        + (proxy == null ? "null" : Redaction.redactUrl(proxy))
        + ", headers="
        + Redaction.redactHeaders(headers)
        + ", userAgentSuffix="
        + userAgentSuffix
        + "}";
  }

  /**
   * Builder of the configuration.
   *
   * <p>Unset fields take the environment (when {@link #fromEnv(EnvReader)} is called) or the
   * default.
   */
  public static final class Builder {
    private String url;
    private String otlpEndpoint;
    private TracingConfig.Protocol protocol;
    private String serviceName;
    private String serviceVersion;
    private String environment;
    private final Map<String, String> tags = new LinkedHashMap<>();
    private Secret apiKey;
    private Secret appKey;
    private String site;
    private String apiUrl;
    private Duration connectTimeout;
    private Duration requestTimeout;
    private Duration totalTimeout;
    private RetryPolicy retry;
    private TlsOptions tls;
    private String proxy;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private String userAgentSuffix;
    private Hooks hooks;
    private Logger logger;

    private Builder() {}

    /**
     * Fills every field that was not set explicitly from the environment (see the table on {@link
     * DatadogApmConfig}).
     *
     * @param env environment reader
     * @return this builder
     * @throws ConfigException when a variable holds an invalid value
     */
    public Builder fromEnv(EnvReader env) {
      if (url == null) {
        url = env.string("URL", URL_VENDOR_VARS).orElse(null);
      }
      if (url == null) {
        url = agentUrlFromEnv(env);
      }
      if (protocol == null) {
        Optional<String> raw = env.string("OTLP_PROTOCOL", PROTOCOL_VENDOR_VARS);
        if (raw.isPresent()) {
          String p = raw.get().trim().toLowerCase(Locale.ROOT);
          if (p.equals("grpc")) {
            protocol = TracingConfig.Protocol.GRPC;
          } else if (p.equals("http/protobuf")) {
            protocol = TracingConfig.Protocol.HTTP_PROTOBUF;
          } else {
            String variable = env.source("OTLP_PROTOCOL", PROTOCOL_VENDOR_VARS).orElse("");
            throw new ConfigException(
                "config: invalid value in " + variable + " (expected grpc or http/protobuf)");
          }
        }
      }
      if (otlpEndpoint == null) {
        otlpEndpoint = otlpEndpointFromEnv(env, protocol == null ? DEFAULT_PROTOCOL : protocol);
      }
      if (serviceName == null) {
        serviceName = trimmed(env, "SERVICE", SERVICE_VENDOR_VARS);
      }
      if (serviceVersion == null) {
        serviceVersion = trimmed(env, "VERSION", VERSION_VENDOR_VARS);
      }
      if (environment == null) {
        environment = trimmed(env, "ENV", ENV_VENDOR_VARS);
      }
      if (site == null) {
        site = trimmed(env, "SITE", SITE_VENDOR_VARS);
      }
      if (apiUrl == null) {
        apiUrl = trimmed(env, "API_URL");
      }
      Optional<String> rawTags = env.string("TAGS", TAGS_VENDOR_VARS);
      if (rawTags.isPresent()) {
        Map<String, String> merged = parseDatadogTags(rawTags.get());
        merged.putAll(tags);
        tags.clear();
        tags.putAll(merged);
      }
      if (apiKey == null) {
        apiKey = env.string("API_KEY", API_KEY_VENDOR_VARS).map(Secret::of).orElse(null);
      }
      if (appKey == null) {
        appKey = env.string("APP_KEY", APP_KEY_VENDOR_VARS).map(Secret::of).orElse(null);
      }
      if (connectTimeout == null) {
        connectTimeout = env.duration("CONNECT_TIMEOUT").orElse(null);
      }
      if (requestTimeout == null) {
        requestTimeout = env.duration("REQUEST_TIMEOUT").orElse(null);
      }
      if (totalTimeout == null) {
        totalTimeout = env.duration("TOTAL_TIMEOUT").orElse(null);
      }
      if (retry == null && env.integer("MAX_RETRIES").isPresent()) {
        int n = env.integer("MAX_RETRIES").getAsInt();
        if (n < 0) {
          String variable = EnvReader.DEFAULT_PREFIX + "MAX_RETRIES";
          throw new ConfigException("config: invalid value in " + variable + " (expected >= 0)");
        }
        retry = RetryPolicy.builder().maxRetries(n).build();
      }
      if (tls == null) {
        TlsOptions.Builder t = TlsOptions.builder();
        env.string("CA_CERT").ifPresent(p -> t.caCertPath(Paths.get(p)));
        env.string("CLIENT_CERT").ifPresent(p -> t.clientCertPath(Paths.get(p)));
        env.string("CLIENT_KEY").ifPresent(p -> t.clientKeyPath(Paths.get(p)));
        env.bool("INSECURE_SKIP_VERIFY").ifPresent(t::insecureSkipVerify);
        tls = t.build();
      }
      if (proxy == null) {
        proxy = env.string("PROXY").orElse(null);
      }
      return this;
    }

    private static String trimmed(EnvReader env, String name, String... vendor) {
      return env.string(name, vendor).map(String::trim).orElse(null);
    }

    /** {@code http://${DD_AGENT_HOST}:${DD_TRACE_AGENT_PORT}}, or null when neither is set. */
    private static String agentUrlFromEnv(EnvReader env) {
      String host = env.string("", "DD_AGENT_HOST").map(String::trim).orElse("");
      Optional<String> rawPort = env.string("", "DD_TRACE_AGENT_PORT").map(String::trim);
      int port = AGENT_TRACE_PORT;
      if (rawPort.isPresent()) {
        String p = rawPort.get();
        int n = DatadogIds.isDigits(p) && p.length() <= 5 ? Integer.parseInt(p) : -1;
        if (n < 1 || n > 65535) {
          throw new ConfigException(
              "config: invalid value in DD_TRACE_AGENT_PORT (expected a port number)");
        }
        port = n;
      }
      if (host.isEmpty() && rawPort.isEmpty()) {
        return null;
      }
      if (host.isEmpty()) {
        host = "127.0.0.1";
      }
      String h = host.replace("[", "").replace("]", "");
      if (h.contains(":")) {
        h = "[" + h + "]";
      }
      return "http://" + h + ":" + port;
    }

    private static String otlpEndpointFromEnv(EnvReader env, TracingConfig.Protocol protocol) {
      String specific =
          protocol == TracingConfig.Protocol.GRPC ? "OTLP_GRPC_ENDPOINT" : "OTLP_HTTP_ENDPOINT";
      Optional<String> v =
          env.string(
              specific,
              EnvReader.DEFAULT_PREFIX + "OTLP_ENDPOINT",
              "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT");
      if (v.isPresent()) {
        return v.get().trim();
      }
      Optional<String> base = env.string("", "OTEL_EXPORTER_OTLP_ENDPOINT").map(String::trim);
      if (base.isEmpty()) {
        return null;
      }
      return protocol == TracingConfig.Protocol.GRPC
          ? base.get()
          : base.get().replaceAll("/+$", "") + "/v1/traces";
    }

    /**
     * Sets the Agent trace intake URL (default {@code http://127.0.0.1:8126}).
     *
     * @param url {@code http} or {@code https} URL
     * @return this builder
     */
    public Builder url(String url) {
      this.url = url;
      return this;
    }

    /**
     * Sets the OTLP endpoint of the Agent. HTTP: a base URL ({@code http://agent:4318}; {@code
     * /v1/traces} is appended when the URL has no path) or the full traces URL; gRPC: {@code
     * http://agent:4317}. Unset: the host of {@link #url(String)} with port 4318 or 4317.
     *
     * @param otlpEndpoint endpoint URL
     * @return this builder
     */
    public Builder otlpEndpoint(String otlpEndpoint) {
      this.otlpEndpoint = otlpEndpoint;
      return this;
    }

    /**
     * Sets the OTLP protocol ({@code HTTP_PROTOBUF} by default, or {@code GRPC}).
     *
     * @param protocol protocol
     * @return this builder
     */
    public Builder protocol(TracingConfig.Protocol protocol) {
      this.protocol = protocol;
      return this;
    }

    /**
     * Sets {@code service} of unified service tagging.
     *
     * @param serviceName service name
     * @return this builder
     */
    public Builder serviceName(String serviceName) {
      this.serviceName = serviceName;
      return this;
    }

    /**
     * Sets {@code version} of unified service tagging.
     *
     * @param serviceVersion version
     * @return this builder
     */
    public Builder serviceVersion(String serviceVersion) {
      this.serviceVersion = serviceVersion;
      return this;
    }

    /**
     * Sets {@code env} of unified service tagging.
     *
     * @param environment environment
     * @return this builder
     */
    public Builder environment(String environment) {
      this.environment = environment;
      return this;
    }

    /**
     * Adds an extra resource attribute (merged over {@code DD_TAGS}).
     *
     * @param key key
     * @param value value
     * @return this builder
     */
    public Builder tag(String key, String value) {
      this.tags.put(key, value);
      return this;
    }

    /**
     * Adds extra resource attributes.
     *
     * @param tags tags
     * @return this builder
     */
    public Builder tags(Map<String, String> tags) {
      this.tags.putAll(tags);
      return this;
    }

    /**
     * Sets the Datadog API key (query API only).
     *
     * @param apiKey key
     * @return this builder
     */
    public Builder apiKey(Secret apiKey) {
      this.apiKey = apiKey;
      return this;
    }

    /**
     * Sets the Datadog API key (query API only).
     *
     * @param apiKey key
     * @return this builder
     */
    public Builder apiKey(String apiKey) {
      this.apiKey = apiKey == null ? null : Secret.of(apiKey);
      return this;
    }

    /**
     * Sets the Datadog application key with {@code apm_read} (query API only).
     *
     * @param appKey key
     * @return this builder
     */
    public Builder appKey(Secret appKey) {
      this.appKey = appKey;
      return this;
    }

    /**
     * Sets the Datadog application key with {@code apm_read} (query API only).
     *
     * @param appKey key
     * @return this builder
     */
    public Builder appKey(String appKey) {
      this.appKey = appKey == null ? null : Secret.of(appKey);
      return this;
    }

    /**
     * Sets the Datadog site, e.g. {@code datadoghq.eu} (default {@code datadoghq.com}).
     *
     * @param site site host name
     * @return this builder
     */
    public Builder site(String site) {
      this.site = site;
      return this;
    }

    /**
     * Sets the query API base URL (default {@code https://api.<site>}).
     *
     * @param apiUrl URL
     * @return this builder
     */
    public Builder apiUrl(String apiUrl) {
      this.apiUrl = apiUrl;
      return this;
    }

    /**
     * Sets the connect timeout (default 10 s).
     *
     * @param d positive duration
     * @return this builder
     */
    public Builder connectTimeout(Duration d) {
      this.connectTimeout = d;
      return this;
    }

    /**
     * Sets the per-attempt timeout (default 30 s).
     *
     * @param d positive duration
     * @return this builder
     */
    public Builder requestTimeout(Duration d) {
      this.requestTimeout = d;
      return this;
    }

    /**
     * Sets the total timeout per call including retries (default 120 s).
     *
     * @param d positive duration
     * @return this builder
     */
    public Builder totalTimeout(Duration d) {
      this.totalTimeout = d;
      return this;
    }

    /**
     * Sets the retry policy (default {@link RetryPolicy#defaults()}).
     *
     * @param retry policy
     * @return this builder
     */
    public Builder retry(RetryPolicy retry) {
      this.retry = retry;
      return this;
    }

    /**
     * Sets the TLS options; environment TLS variables are then ignored.
     *
     * @param tls options
     * @return this builder
     */
    public Builder tls(TlsOptions tls) {
      this.tls = tls;
      return this;
    }

    /**
     * Sets an explicit proxy URL; environment proxies ({@code HTTPS_PROXY}) are never used.
     *
     * @param proxy proxy URL
     * @return this builder
     */
    public Builder proxy(String proxy) {
      this.proxy = proxy;
      return this;
    }

    /**
     * Adds a header sent with every request.
     *
     * @param name header name
     * @param value header value
     * @return this builder
     */
    public Builder header(String name, String value) {
      this.headers.put(name, value);
      return this;
    }

    /**
     * Sets the text appended to the {@code User-Agent}.
     *
     * @param suffix suffix
     * @return this builder
     */
    public Builder userAgentSuffix(String suffix) {
      this.userAgentSuffix = suffix;
      return this;
    }

    /**
     * Sets request hooks.
     *
     * @param hooks hooks
     * @return this builder
     */
    public Builder hooks(Hooks hooks) {
      this.hooks = hooks;
      return this;
    }

    /**
     * Sets the logger (default silent).
     *
     * @param logger logger
     * @return this builder
     */
    public Builder logger(Logger logger) {
      this.logger = logger;
      return this;
    }

    /**
     * Builds and validates the configuration.
     *
     * @return the configuration
     * @throws ConfigException when a rule is violated
     */
    public DatadogApmConfig build() {
      DatadogApmConfig c = new DatadogApmConfig(this);
      c.validate();
      return c;
    }
  }
}
