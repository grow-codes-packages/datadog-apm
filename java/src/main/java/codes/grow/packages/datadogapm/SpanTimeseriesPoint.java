package codes.grow.packages.datadogapm;

/**
 * A point of a timeseries compute of {@link DatadogSpansQuery#aggregateSpans}.
 *
 * @param time bucket time (ISO-8601)
 * @param value value
 */
public record SpanTimeseriesPoint(String time, double value) {}
