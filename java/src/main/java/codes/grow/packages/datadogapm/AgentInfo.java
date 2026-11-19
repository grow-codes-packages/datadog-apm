package codes.grow.packages.datadogapm;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * The Datadog Agent's {@code GET /info} document (fields this package reads).
 *
 * @param version Agent version, e.g. {@code 7.85.4} (empty when not reported)
 * @param gitCommit Agent build commit (empty when not reported)
 * @param endpoints trace intake endpoints the Agent serves
 * @param config the Agent's reported configuration
 * @param raw the whole document
 */
public record AgentInfo(
    String version, String gitCommit, List<String> endpoints, JsonNode config, JsonNode raw) {
  /**
   * Copies the endpoints.
   *
   * @param version version
   * @param gitCommit commit
   * @param endpoints endpoints
   * @param config config
   * @param raw document
   */
  public AgentInfo {
    endpoints = List.copyOf(endpoints);
  }
}
