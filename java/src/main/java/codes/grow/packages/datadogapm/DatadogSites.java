package codes.grow.packages.datadogapm;

import codes.grow.packages.datadogapm.core.ConfigException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Datadog sites and the query API base URL (API.md section 0). Same site handling as the {@code
 * datadog-logs} package; this copy is kept in sync by hand (no cross-repository runtime
 * dependency).
 */
public final class DatadogSites {
  /** Default Datadog site (US1). */
  public static final String DEFAULT_SITE = "datadoghq.com";

  /**
   * The Datadog sites of the official OpenAPI spec (servers enum). Other host names are accepted
   * too (the spec's "any Datadog deployment" server), but never a scheme, port or path.
   */
  public static final List<String> DATADOG_SITES =
      List.of(
          "datadoghq.com",
          "us3.datadoghq.com",
          "us5.datadoghq.com",
          "ap1.datadoghq.com",
          "ap2.datadoghq.com",
          "uk1.datadoghq.com",
          "datadoghq.eu",
          "ddog-gov.com",
          "us2.ddog-gov.com");

  private static final Pattern SITE_HOST =
      Pattern.compile("^[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$");

  private DatadogSites() {}

  /**
   * Lower-cases and trims a site name.
   *
   * @param site site name
   * @return the normalized name ({@code ""} for {@code null})
   */
  public static String normalizeSite(String site) {
    return site == null ? "" : site.trim().toLowerCase(Locale.ROOT);
  }

  /**
   * Whether {@code site} is one of {@link #DATADOG_SITES}.
   *
   * @param site site name
   * @return true for a known site
   */
  public static boolean isDatadogSite(String site) {
    return DATADOG_SITES.contains(normalizeSite(site));
  }

  /**
   * Validates a site name and returns it normalized.
   *
   * @param site site name such as {@code datadoghq.eu}
   * @return the normalized site
   * @throws ConfigException for a value with a scheme, port, path or other non-host characters (for
   *     example {@code https://app.datadoghq.com})
   */
  public static String validateSite(String site) {
    String s = normalizeSite(site);
    if (s.length() > 253 || !SITE_HOST.matcher(s).matches()) {
      throw new ConfigException(
          "site must be a Datadog site host name such as datadoghq.com or datadoghq.eu (no scheme"
              + " or path)");
    }
    return s;
  }

  /**
   * Returns the API base URL of a site: {@code https://api.<site>}.
   *
   * <pre>{@code
   * DatadogSites.apiUrlForSite("datadoghq.eu"); // "https://api.datadoghq.eu"
   * }</pre>
   *
   * @param site site name; {@code null} or blank means {@link #DEFAULT_SITE}
   * @return the API base URL
   * @throws ConfigException for an invalid site
   */
  public static String apiUrlForSite(String site) {
    String s = site == null || site.isBlank() ? DEFAULT_SITE : site;
    return "https://api." + validateSite(s);
  }
}
