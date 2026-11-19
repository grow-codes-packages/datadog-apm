package codes.grow.packages.datadogapm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import codes.grow.packages.datadogapm.core.ConfigException;
import codes.grow.packages.datadogapm.core.ValidationException;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

class DatadogIdsTest {
  @Test
  void vectorsMatchTheAgent() {
    for (JsonNode v : Fixtures.load("toDatadogTraceId.vectors.json")) {
      String hex = v.get("otel_trace_id_hex").asText();
      DatadogIds.DatadogTraceId id = DatadogIds.toDatadogTraceId(hex);
      assertThat(id.low64Decimal()).isEqualTo(v.get("datadog_trace_id_low64_decimal").asText());
      assertThat(id.high64Hex()).isEqualTo(v.get("datadog_trace_id_high64_hex").asText());
      assertThat(id.is128Bit()).isEqualTo(!v.get("x-datadog-tags").isNull());
      assertThat(DatadogIds.fromDatadogTraceId(id.low64Decimal(), id.high64Hex())).isEqualTo(hex);
      assertThat(DatadogIds.datadogTraceIdString(hex))
          .isEqualTo(id.is128Bit() ? hex : id.low64Decimal());
      assertThat(DatadogIds.normalizeDatadogTraceId(DatadogIds.datadogTraceIdString(hex)))
          .isEqualTo(hex);
    }
  }

  @Test
  void conversions() {
    assertThat(DatadogIds.fromDatadogTraceId("42")).isEqualTo("0000000000000000000000000000002a");
    assertThat(DatadogIds.fromDatadogTraceId("42", "")).endsWith("2a");
    assertThat(DatadogIds.fromDatadogTraceId(" 1 ", "FFFFFFFFFFFFFFFF"))
        .isEqualTo("ffffffffffffffff0000000000000001");
    assertThat(DatadogIds.toDatadogTraceId("2a").low64Decimal()).isEqualTo("42");
    assertThat(DatadogIds.toDatadogSpanId("00f067aa0ba902b7")).isEqualTo("67667974448284343");
    assertThat(DatadogIds.toDatadogSpanId("FFFFFFFFFFFFFFFF")).isEqualTo("18446744073709551615");
    assertThat(DatadogIds.fromDatadogSpanId("255")).isEqualTo("00000000000000ff");
    assertThat(DatadogIds.normalizeDatadogSpanId("")).isEmpty();
    assertThat(DatadogIds.normalizeDatadogSpanId(null)).isEmpty();
    assertThat(DatadogIds.normalizeDatadogSpanId("000")).isEmpty();
    assertThat(DatadogIds.normalizeDatadogSpanId("1")).isEqualTo("0000000000000001");
    assertThat(DatadogIds.normalizeDatadogTraceId("ABC")).isEqualTo("0".repeat(29) + "abc");
    assertThat(DatadogIds.normalizeDatadogTraceId("123")).isEqualTo("0".repeat(30) + "7b");
    assertThat(DatadogIds.normalizeDatadogTraceId("a3ce929d0e0e47364bf92f3577b34da6"))
        .isEqualTo("a3ce929d0e0e47364bf92f3577b34da6");
    assertThat(DatadogIds.traceIdForPath("0000000000000000abc1230000000000"))
        .isEqualTo("0000000000000000abc1230000000000");
    assertThat(DatadogIds.traceIdForPath("00000000000000000020000000000001"))
        .isEqualTo("9007199254740993");
    assertThat(DatadogIds.traceIdForPath("10000000000000000000000000000000"))
        .isEqualTo("21267647932558653966460912964485513216");
  }

  @Test
  void invalidIds() {
    assertThatThrownBy(() -> DatadogIds.toDatadogTraceId(""))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> DatadogIds.toDatadogTraceId(null))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> DatadogIds.toDatadogTraceId("xyz"))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> DatadogIds.toDatadogTraceId("0".repeat(33)))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> DatadogIds.toDatadogTraceId("000")).hasMessageContaining("all zeros");
    assertThatThrownBy(() -> DatadogIds.fromDatadogTraceId("18446744073709551616"))
        .hasMessageContaining("2^64-1");
    assertThatThrownBy(() -> DatadogIds.fromDatadogTraceId("-1"))
        .hasMessageContaining("unsigned decimal");
    assertThatThrownBy(() -> DatadogIds.fromDatadogTraceId(null))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> DatadogIds.fromDatadogTraceId("1", "abc"))
        .hasMessageContaining("16 hex");
    assertThatThrownBy(() -> DatadogIds.fromDatadogTraceId("1", "zzzzzzzzzzzzzzzz"))
        .hasMessageContaining("16 hex");
    assertThatThrownBy(() -> DatadogIds.fromDatadogTraceId("0", "0000000000000000"))
        .hasMessageContaining("all zeros");
    assertThatThrownBy(() -> DatadogIds.toDatadogSpanId(""))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> DatadogIds.toDatadogSpanId(null))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> DatadogIds.toDatadogSpanId("1".repeat(17)))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> DatadogIds.toDatadogSpanId("g"))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> DatadogIds.fromDatadogSpanId("x"))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> DatadogIds.normalizeDatadogTraceId(null))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void sites() {
    assertThat(DatadogSites.DATADOG_SITES).hasSize(9).contains("datadoghq.eu");
    assertThat(DatadogSites.isDatadogSite(" DatadogHQ.EU ")).isTrue();
    assertThat(DatadogSites.isDatadogSite("example.com")).isFalse();
    assertThat(DatadogSites.normalizeSite(null)).isEmpty();
    assertThat(DatadogSites.validateSite("dd.internal.example")).isEqualTo("dd.internal.example");
    assertThat(DatadogSites.apiUrlForSite("datadoghq.eu")).isEqualTo("https://api.datadoghq.eu");
    assertThat(DatadogSites.apiUrlForSite(null)).isEqualTo("https://api.datadoghq.com");
    assertThat(DatadogSites.apiUrlForSite(" ")).isEqualTo("https://api.datadoghq.com");
    assertThatThrownBy(() -> DatadogSites.validateSite("https://app.datadoghq.com"))
        .isInstanceOf(ConfigException.class);
    assertThatThrownBy(() -> DatadogSites.validateSite("a.b/c"))
        .isInstanceOf(ConfigException.class);
    assertThatThrownBy(() -> DatadogSites.validateSite("localhost"))
        .isInstanceOf(ConfigException.class);
    assertThatThrownBy(() -> DatadogSites.validateSite("a." + "b".repeat(260)))
        .isInstanceOf(ConfigException.class);
  }
}
