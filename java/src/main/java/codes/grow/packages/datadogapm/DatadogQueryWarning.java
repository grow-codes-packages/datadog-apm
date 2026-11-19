package codes.grow.packages.datadogapm;

/**
 * A warning attached to a partial or adjusted Datadog query result ({@code meta.warnings[]}).
 *
 * @param code warning code
 * @param title short title
 * @param detail detail text
 */
public record DatadogQueryWarning(String code, String title, String detail) {}
