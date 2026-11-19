package codes.grow.packages.datadogapm;

import codes.grow.packages.datadogapm.core.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/** Recorded fixtures from the Docker prep (test resources {@code fixtures/}). */
final class Fixtures {
  private Fixtures() {}

  static JsonNode load(String name) {
    try (InputStream in = Fixtures.class.getResourceAsStream("/fixtures/" + name)) {
      if (in == null) {
        throw new IllegalArgumentException("missing fixture " + name);
      }
      return Json.tree(in.readAllBytes());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static String body(String name) {
    return Json.stringify(load(name).get("body"));
  }
}
