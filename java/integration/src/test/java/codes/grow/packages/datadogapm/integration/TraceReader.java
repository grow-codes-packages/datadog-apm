package codes.grow.packages.datadogapm.integration;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import codes.grow.packages.datadogapm.DatadogMapping;
import codes.grow.packages.datadogapm.core.Json;
import codes.grow.packages.datadogapm.tracing.query.Span;
import codes.grow.packages.datadogapm.tracing.query.Trace;
import codes.grow.packages.datadogapm.tracing.query.TraceIds;
import codes.grow.packages.datadogapm.tracing.query.TraceQueryClient;
import codes.grow.packages.datadogapm.tracing.query.TraceSearchQuery;
import codes.grow.packages.datadogapm.tracing.query.TraceSummary;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeSet;

/**
 * Test-only access to the spans the real Datadog Agent forwarded to fakeintake, decoded by the
 * trace-reader sidecar ({@code GET /spans?trace_id=<hex>&service=...}). Not part of the package:
 * the Datadog query API is SaaS-only, so the suite reads the Agent's output here and maps it with
 * the package's own {@link DatadogMapping#traceSpanToSpan}.
 */
final class TraceReader implements TraceQueryClient {
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  private final String base;

  TraceReader(Map<String, String> env) {
    this.base = env.getOrDefault("TRACE_READER_URL", "").replaceAll("/+$", "");
  }

  static Map<String, String> env() {
    try {
      return ConformanceTest.environment();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Skips when the Docker harness is not running. */
  static Map<String, String> requireService() {
    Map<String, String> env = env();
    assumeTrue(
        env.containsKey("GROW_DATADOG_APM_URL") && env.containsKey("TRACE_READER_URL"),
        "GROW_DATADOG_APM_URL / TRACE_READER_URL not set; run through docker/compose.yml");
    return env;
  }

  static String runId() {
    return env().getOrDefault("GROW_TEST_RUN_ID", "local");
  }

  List<JsonNode> spans(Map<String, String> filters) {
    StringJoiner q = new StringJoiner("&");
    filters.forEach(
        (k, v) ->
            q.add(
                URLEncoder.encode(k, StandardCharsets.UTF_8)
                    + "="
                    + URLEncoder.encode(v, StandardCharsets.UTF_8)));
    try {
      HttpResponse<byte[]> r =
          HTTP.send(
              HttpRequest.newBuilder(URI.create(base + "/spans?" + q))
                  .timeout(Duration.ofSeconds(10))
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());
      if (r.statusCode() != 200) {
        throw new IllegalStateException(
            "trace-reader: HTTP " + r.statusCode() + " " + new String(r.body(), StandardCharsets.UTF_8));
      }
      JsonNode doc = Json.tree(r.body());
      if (doc.path("undecodable_idx_payloads").asInt() != 0) {
        throw new IllegalStateException("trace-reader: undecodable payloads");
      }
      List<JsonNode> out = new ArrayList<>();
      doc.path("spans").forEach(out::add);
      return out;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** Polls until at least {@code min} spans match (the Agent flushes every ~4 s). */
  List<JsonNode> waitForSpans(Map<String, String> filters, int min) {
    long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
    List<JsonNode> last = List.of();
    while (System.nanoTime() < deadline) {
      last = spans(filters);
      if (last.size() >= min) {
        return last;
      }
      try {
        Thread.sleep(500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
    throw new AssertionError("trace-reader: " + last.size() + "/" + min + " spans for " + filters);
  }

  @Override
  public Optional<Trace> getTrace(String traceId) {
    String id = TraceIds.normalizeTraceId(traceId);
    List<JsonNode> raws = spans(Map.of("trace_id", id));
    if (raws.isEmpty()) {
      return Optional.empty();
    }
    List<Span> spans = new ArrayList<>();
    for (JsonNode r : raws) {
      spans.add(DatadogMapping.traceSpanToSpan(r));
    }
    return Optional.of(new Trace(id, spans));
  }

  @Override
  public List<TraceSummary> searchTraces(TraceSearchQuery query) {
    Map<String, String> filters = new java.util.LinkedHashMap<>();
    query.service().ifPresent(s -> filters.put("service", s));
    query.operation().ifPresent(s -> filters.put("resource", s));
    Set<String> ids = new LinkedHashSet<>();
    for (JsonNode r : spans(filters)) {
      ids.add(r.path("trace_id_hex").asText());
    }
    List<TraceSummary> out = new ArrayList<>();
    for (String id : ids) {
      if (out.size() >= query.limit()) {
        break;
      }
      getTrace(id)
          .ifPresent(
              t ->
                  out.add(
                      new TraceSummary(
                          id,
                          Optional.empty(),
                          Optional.empty(),
                          t.spans().get(0).startMicros(),
                          0,
                          t.spans().size())));
    }
    return out;
  }

  private List<String> distinct(Map<String, String> filters, String key) {
    TreeSet<String> out = new TreeSet<>();
    for (JsonNode r : spans(filters)) {
      out.add(r.path(key).asText());
    }
    return List.copyOf(out);
  }

  @Override
  public List<String> listServices() {
    return distinct(Map.of(), "service");
  }

  @Override
  public List<String> listOperations(String service) {
    return distinct(Map.of("service", service), "resource");
  }
}
