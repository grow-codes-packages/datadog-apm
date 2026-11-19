package codes.grow.packages.datadogapm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import codes.grow.packages.datadogapm.core.Json;
import codes.grow.packages.datadogapm.core.ValidationException;
import codes.grow.packages.datadogapm.tracing.query.Span;
import codes.grow.packages.datadogapm.tracing.query.Traces;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DatadogMappingTest {
  private static JsonNode json(String text) {
    return Json.tree(text.replace('\'', '"').getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void agentOutput() {
    List<Span> spans = new ArrayList<>();
    for (JsonNode raw : Fixtures.load("initTracing.otlp_http_json.agent_spans.json")) {
      spans.add(DatadogMapping.traceSpanToSpan(raw));
    }
    Map<String, Span> byName = new LinkedHashMap<>();
    spans.forEach(s -> byName.put(s.name(), s));
    assertThat(byName.keySet())
        .containsExactly("GET /items/{id}", "POST", "compute", "orders publish", "orders process");
    Span root = byName.get("GET /items/{id}");
    assertThat(root.traceId()).isEqualTo("0123456789abcdefffffffffffffffff");
    assertThat(root.spanId()).isEqualTo("00000000000000ff");
    assertThat(root.kind()).isEqualTo(Span.Kind.SERVER);
    assertThat(root.startMicros()).isEqualTo(1795035814558726L);
    assertThat(root.durationMicros()).isEqualTo(9000);
    assertThat(root.endMicros()).isEqualTo(1795035814567726L);
    assertThat(root.status().code()).isEqualTo(Span.StatusCode.UNSET);
    assertThat(root.parentSpanId()).isEmpty();
    assertThat(root.attributes())
        .containsEntry("operation_name", "http.server.request")
        .containsEntry("resource_name", "GET /items/{id}")
        .containsEntry("span.type", "web")
        .containsEntry("grow.ratio", 0.5);
    Span post = byName.get("POST");
    assertThat(post.kind()).isEqualTo(Span.Kind.CLIENT);
    assertThat(post.parentSpanId()).contains("00000000000000ff");
    assertThat(post.status().code()).isEqualTo(Span.StatusCode.ERROR);
    assertThat(post.status().message()).contains("boom");
    assertThat(byName.get("orders process").status().code()).isEqualTo(Span.StatusCode.OK);
    assertThat(byName.get("orders publish").kind()).isEqualTo(Span.Kind.PRODUCER);
    assertThat(Traces.buildTree(spans))
        .singleElement()
        .satisfies(n -> assertThat(n.children()).hasSize(3));
    Span compute = byName.get("compute");
    assertThat(compute.events())
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.name()).isEqualTo("cache.miss");
              assertThat(e.timeMicros()).isPositive();
              assertThat(e.attributes()).isEqualTo(Map.of("key", "k1", "n", 3L));
            });
    assertThat(compute.links())
        .singleElement()
        .satisfies(
            l -> {
              assertThat(l.traceId()).isEqualTo("a3ce929d0e0e47364bf92f3577b34da6");
              assertThat(l.spanId()).isEqualTo("00f067aa0ba902b7");
              assertThat(l.attributes()).containsEntry("link.kind", "follows");
            });
    assertThat(compute.attributes()).doesNotContainKey("events");

    Span probe =
        DatadogMapping.traceSpanToSpan(
            Fixtures.load("traceReader.spans.ok.json").get("spans").get(0));
    assertThat(probe.traceId()).isEqualTo("69a2af8e68444220f1bf29ab7bd501bf");
    assertThat(probe.spanId()).isEqualTo("4445fd1b19708db1");
    assertThat(probe.name()).isEqualTo("bootstrap.probe");
    assertThat(probe.serviceName()).isEqualTo("grow-bootstrap-probe");
    assertThat(probe.kind()).isEqualTo(Span.Kind.INTERNAL);
    assertThat(probe.attributes()).containsEntry("_top_level", 1.0);
    JsonNode sdk = Fixtures.load("initTracing.otel_sdk_http.agent_spans.json");
    assertThat(DatadogMapping.traceSpanToSpan(sdk.get(0)).attributes()).containsEntry("n", 7.0);
    assertThat(DatadogMapping.traceSpanToSpan(sdk.get(1)).events().get(0).name()).isEqualTo("rows");
  }

  @Test
  void byIdApi() {
    JsonNode raw =
        Fixtures.load("getTraceById.docs_example.json").at("/body/data/attributes/spans/0");
    Span s = DatadogMapping.traceSpanToSpan(raw);
    assertThat(s.traceId()).isEqualTo("0000000000000000abc1230000000000");
    assertThat(s.spanId()).isEqualTo("891087b8e3b70cb1");
    assertThat(s.name()).isEqualTo("GET /products");
    assertThat(s.serviceName()).isEqualTo("web-store");
    assertThat(s.startMicros()).isEqualTo(1716800000000000L);
    assertThat(s.endMicros()).isEqualTo(1716800000500000L);
    assertThat(s.durationMicros()).isEqualTo(500000);
    assertThat(s.kind()).isEqualTo(Span.Kind.UNSPECIFIED);
    assertThat(s.attributes())
        .containsEntry("http.method", "GET")
        .containsEntry("http.status_code", 200L);
  }

  @Test
  void traceSpanFallbacks() {
    JsonNode raw =
        json(
            "{'traceID': '1', 'spanID': 2, 'parentID': '3', 'startTime': '1000000', 'endTime': '4000000',"
                + " 'name': 'op', 'service': 's', 'error': 1,"
                + " 'meta': {'_dd.p.tid': 'ffffffffffffffff', 'otel.status_description': 'desc', 'span.kind': 'SERVER', 'num': 5, 'events': 'not json'},"
                + " 'metrics': {'m': '2.5', 'bad': 'x', 'empty': '', 'inf': 'Infinity', 'obj': {}},"
                + " 'span_events': ["
                + "  {'time_unix_nano': 5000, 'name': 'native', 'attributes': {"
                + "   's': {'type': 0, 'string_value': 'v'}, 'i': {'type': 2, 'int_value': 3},"
                + "   'a': {'type': 4, 'array_value': {'values': [{'type': 0, 'string_value': 'x'}]}},"
                + "   'e': {'type': 4, 'array_value': {}}, 'raw': 'plain'}},"
                + "  {'name': 'no-time'}, 7"
                + " ],"
                + " 'span_links': ["
                + "  {'trace_id': '1', 'trace_id_high': 'ffffffffffffffff', 'span_id': '2', 'attributes': {}},"
                + "  {'trace_id': '1', 'trace_id_high': '255', 'span_id': '2'},"
                + "  {'trace_id': '00f067aa0ba902b7', 'span_id': '00f067aa0ba902b7'},"
                + "  {'trace_id': 'zz-bad', 'span_id': '1'},"
                + "  {'trace_id': '1', 'trace_id_high': 'not-a-number', 'span_id': '1'},"
                + "  {'trace_id': '1', 'span_id': 'x-bad'},"
                + "  {'trace_id': '1', 'span_id': '0'},"
                + "  {'span_id': '1'},"
                + "  {'trace_id': '1'}"
                + " ]}");
    Span s = DatadogMapping.traceSpanToSpan(raw);
    assertThat(s.traceId()).isEqualTo("ffffffffffffffff0000000000000001");
    assertThat(s.spanId()).isEqualTo("0000000000000002");
    assertThat(s.parentSpanId()).contains("0000000000000003");
    assertThat(s.startMicros()).isEqualTo(1000);
    assertThat(s.endMicros()).isEqualTo(4000);
    assertThat(s.durationMicros()).isEqualTo(3000);
    assertThat(s.kind()).isEqualTo(Span.Kind.SERVER);
    assertThat(s.status().code()).isEqualTo(Span.StatusCode.ERROR);
    assertThat(s.status().message()).contains("desc");
    assertThat(s.attributes()).containsEntry("m", 2.5).containsEntry("operation_name", "op");
    assertThat(s.attributes()).doesNotContainKeys("num", "bad", "empty", "inf", "obj");
    assertThat(s.events()).hasSize(3);
    assertThat(s.events().get(0).timeMicros()).isEqualTo(5);
    assertThat(s.events().get(0).attributes())
        .isEqualTo(Map.of("s", "v", "i", 3L, "a", List.of("x"), "e", List.of(), "raw", "plain"));
    assertThat(s.events().get(1).timeMicros()).isZero();
    assertThat(s.links())
        .extracting(Span.Link::traceId)
        .containsExactly(
            "ffffffffffffffff0000000000000001",
            "00000000000000ff0000000000000001",
            "000000000000000000f067aa0ba902b7");

    Span minimal =
        DatadogMapping.traceSpanToSpan(
            Map.of("trace_id", "5", "span_id", "6", "start", 2000.0, "duration", 1000));
    assertThat(minimal.name()).isEmpty();
    assertThat(minimal.serviceName()).isEmpty();
    assertThat(minimal.endMicros()).isEqualTo(3);
    assertThat(minimal.status().code()).isEqualTo(Span.StatusCode.UNSET);
    Span noDuration =
        DatadogMapping.traceSpanToSpan(
            json("{'trace_id': '5', 'span_id': '7', 'error': '1', 'meta': [], 'end': 9000}"));
    assertThat(noDuration.durationMicros()).isEqualTo(9);
    assertThat(noDuration.status().code()).isEqualTo(Span.StatusCode.ERROR);
    assertThat(noDuration.status().message()).isEmpty();
    Span nothing =
        DatadogMapping.traceSpanToSpan(
            json("{'trace_id': '5', 'span_id': '7', 'error': true, 'start': 'x'}"));
    assertThat(nothing.startMicros()).isZero();
    Span full =
        DatadogMapping.traceSpanToSpan(
            json(
                "{'trace_id_hex': 'abc', 'span_id': '1', 'metrics': {'i': 3, 'f': 1.5}, 'error': 'abc'}"));
    assertThat(full.traceId()).isEqualTo("00000000000000000000000000000abc");
    assertThat(full.attributes()).containsEntry("i", 3L).containsEntry("f", 1.5);
    assertThat(full.status().code()).isEqualTo(Span.StatusCode.ERROR);
    Span otel =
        DatadogMapping.traceSpanToSpan(
            json(
                "{'trace_id': '1', 'span_id': '1', 'meta': {'otel.trace_id': 'abcd'}, 'error': ' '}"));
    assertThat(otel.traceId()).isEqualTo("0".repeat(28) + "abcd");
    Span high =
        DatadogMapping.traceSpanToSpan(
            json(
                "{'trace_id': '1', 'trace_id_high': '0000000000000001', 'span_id': '1', 'error': {}}"));
    assertThat(high.traceId()).isEqualTo("00000000000000010000000000000001");
    for (String bad :
        new String[] {
          "{}",
          "{'trace_id': '1', 'span_id': 'x'}",
          "{'trace_id': '1', 'span_id': '1', 'parent_id': 'y'}",
          "{'traceIDFull': 'zz', 'span_id': '1'}",
          "{'trace_id': '1', 'span_id': '0'}"
        }) {
      assertThatThrownBy(() -> DatadogMapping.traceSpanToSpan(json(bad)))
          .isInstanceOf(ValidationException.class);
    }
    assertThat(DatadogMapping.datadogKind("Client")).isEqualTo(Span.Kind.CLIENT);
    assertThat(DatadogMapping.datadogKind("weird")).isEqualTo(Span.Kind.UNSPECIFIED);
    assertThat(DatadogMapping.datadogKind("SPAN_KIND_SERVER")).isEqualTo(Span.Kind.UNSPECIFIED);
    assertThat(DatadogMapping.datadogKind(null)).isEqualTo(Span.Kind.UNSPECIFIED);
  }

  @Test
  void searchSpan() {
    JsonNode raw =
        json(
            "{'id': 'AAAA', 'type': 'spans', 'attributes': {"
                + " 'service': 'web', 'env': 'prod', 'host': 'h1', 'resource_name': 'GET /x', 'operation_name': 'http.server.request',"
                + " 'type': 'web', 'trace_id': '0123456789abcdefffffffffffffffff', 'span_id': '255', 'parent_id': '0',"
                + " 'start_timestamp': '2026-11-18T10:00:00.123456Z', 'end_timestamp': '2026-11-18T10:00:00.223456Z',"
                + " 'tags': ['team:sdk', 'bare', 5],"
                + " 'attributes': {'http': {'status_code': 500}, 'span': {'kind': 'server'}, 'duration': 100000000, 'n': null, 'b': true},"
                + " 'custom': {'grow': {'run': 'abc'}, 'list': [1, null, {'a': 1}]},"
                + " 'status': 'error', 'ingestion_reason': 'rule'}}");
    Span s = DatadogMapping.searchSpanToSpan(raw);
    long start = Instant.parse("2026-11-18T10:00:00Z").getEpochSecond() * 1_000_000L + 123456;
    assertThat(s.traceId()).isEqualTo("0123456789abcdefffffffffffffffff");
    assertThat(s.spanId()).isEqualTo("00000000000000ff");
    assertThat(s.name()).isEqualTo("GET /x");
    assertThat(s.kind()).isEqualTo(Span.Kind.SERVER);
    assertThat(s.serviceName()).isEqualTo("web");
    assertThat(s.durationMicros()).isEqualTo(100000);
    assertThat(s.status().code()).isEqualTo(Span.StatusCode.ERROR);
    assertThat(s.startMicros()).isEqualTo(start);
    assertThat(s.endMicros() - s.startMicros()).isEqualTo(100000);
    assertThat(s.parentSpanId()).isEmpty();
    assertThat(s.attributes())
        .containsEntry("team", "sdk")
        .containsEntry("http.status_code", 500L)
        .containsEntry("grow.run", "abc")
        .containsEntry("list", List.of(1L, Map.of("a", 1L)))
        .containsEntry("env", "prod")
        .containsEntry("host", "h1")
        .containsEntry("span.type", "web")
        .containsEntry("ingestion_reason", "rule")
        .containsEntry("operation_name", "http.server.request")
        .containsEntry("resource_name", "GET /x")
        .containsEntry("b", true);

    JsonNode doc = Fixtures.load("searchSpans.docs_example.json").at("/body/data/0");
    assertThatThrownBy(() -> DatadogMapping.searchSpanToSpan(doc))
        .isInstanceOf(ValidationException.class);
    Span ok =
        DatadogMapping.searchSpanToSpan(
            json(
                "{'trace_id': '42', 'span_id': '1', 'parent_id': '7',"
                    + " 'start_timestamp': '2026-11-18T10:00:00Z', 'end_timestamp': '2026-11-18T10:00:01+00:00',"
                    + " 'attributes': {'error': {'message': 'nested'}, 'otel.status_code': 'Ok'}}"));
    assertThat(ok.traceId()).isEqualTo("0000000000000000000000000000002a");
    assertThat(ok.parentSpanId()).contains("0000000000000007");
    assertThat(ok.durationMicros()).isEqualTo(1_000_000);
    assertThat(ok.status().code()).isEqualTo(Span.StatusCode.OK);
    assertThat(ok.name()).isEmpty();
    Span errored =
        DatadogMapping.searchSpanToSpan(
            json(
                "{'trace_id': '42', 'span_id': '1', 'start_timestamp': 'bad',"
                    + " 'attributes': {'error': 1, 'error.msg': 'm', 'operation_name': 'attr-op', 'resource_name': 'attr-res'}}"));
    assertThat(errored.startMicros()).isZero();
    assertThat(errored.endMicros()).isZero();
    assertThat(errored.status().message()).contains("m");
    assertThat(errored.name()).isEqualTo("attr-res");
    Span noEnd =
        DatadogMapping.searchSpanToSpan(
            json(
                "{'trace_id': '42', 'span_id': '1', 'start_timestamp': '2026-11-18T10:00:00', 'operation_name': 'op',"
                    + " 'attributes': {'duration': '2000', 'status': 'Error', 'error': 'true', 'error.message': 7}}"));
    assertThat(noEnd.endMicros() - noEnd.startMicros()).isEqualTo(2);
    assertThat(noEnd.name()).isEqualTo("op");
    assertThat(noEnd.status().code()).isEqualTo(Span.StatusCode.ERROR);
    assertThat(noEnd.status().message()).contains("7");
    Span plain =
        DatadogMapping.searchSpanToSpan(
            json("{'trace_id': '42', 'span_id': '1', 'start_timestamp': 5}"));
    assertThat(plain.startMicros()).isZero();
    for (String bad :
        new String[] {
          "{'span_id': '1', 'trace_id': 'zz'}",
          "{'trace_id': '1', 'span_id': 'x'}",
          "{'trace_id': '1', 'span_id': '1', 'parent_id': 'x'}"
        }) {
      assertThatThrownBy(() -> DatadogMapping.searchSpanToSpan(json(bad)))
          .isInstanceOf(ValidationException.class);
    }
  }

  @Test
  void helpers() {
    assertThat(DatadogMapping.str(json("1.0"))).isEqualTo("1");
    assertThat(DatadogMapping.str(json("1.5"))).isEqualTo("1.5");
    assertThat(DatadogMapping.str(json("1e300"))).isEqualTo("1.0E300");
    assertThat(DatadogMapping.str(json("true"))).isEmpty();
    assertThat(DatadogMapping.str(null)).isEmpty();
    assertThat(DatadogMapping.str(json("12376211733897805824"))).isEqualTo("12376211733897805824");
    assertThat(DatadogMapping.numericTruthy(null)).isFalse();
    assertThat(DatadogMapping.numericTruthy(json("true"))).isTrue();
    assertThat(DatadogMapping.numericTruthy(json("''"))).isFalse();
    assertThat(DatadogMapping.numericTruthy(json("'abc'"))).isTrue();
    assertThat(DatadogMapping.numericTruthy(json("'0'"))).isFalse();
    assertThat(DatadogMapping.numericTruthy(json("{}"))).isFalse();
    assertThat(DatadogMapping.numericTruthy(json("2"))).isTrue();
    assertThat(DatadogMapping.numericTruthy(json("0.0"))).isFalse();
    assertThat(DatadogMapping.int64(json("1.5e3"))).isEqualTo(1500L);
    assertThat(DatadogMapping.int64(json("'x'"))).isNull();
    assertThat(DatadogMapping.int64(json("true"))).isNull();
    assertThat(DatadogMapping.int64(null)).isNull();
    assertThat(DatadogMapping.number(json("12376211733897805824")))
        .isEqualTo(1.2376211733897806E19);
    assertThat(DatadogMapping.number(null)).isNull();
    assertThat(DatadogMapping.attrValue(json("null"))).isNull();
    assertThat(DatadogMapping.attrValue(null)).isNull();
    assertThat(DatadogMapping.isoToMicros(null)).isNull();
    assertThat(DatadogMapping.isoToMicros(json("''"))).isNull();
  }
}
