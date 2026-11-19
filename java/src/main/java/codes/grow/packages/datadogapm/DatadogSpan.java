package codes.grow.packages.datadogapm;

import codes.grow.packages.datadogapm.tracing.query.Span;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * One span returned by the spans search API.
 *
 * @param id Datadog event id of the span
 * @param span the span in the SPEC section 5 model (ids normalized to hex)
 * @param raw the attributes exactly as Datadog returned them
 */
public record DatadogSpan(String id, Span span, JsonNode raw) {}
