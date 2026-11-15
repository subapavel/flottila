package io.flottila.mapping.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The service binds to {@code 127.0.0.1} in every profile (ADR-0014, "Running"; #28 design,
 * "Config"): no profile of any {@code application*.properties} moves {@code quarkus.http.host}
 * elsewhere. Surefire runs in the module directory.
 */
class ApplicationPropertiesTest {

  private static final Path RESOURCES = Path.of("src", "main", "resources");
  private static final String HOST = "quarkus.http.host";

  /** {@code %dev.key}, {@code %dev,test.key}: the profile prefix of a key. */
  private static final Pattern PROFILED = Pattern.compile("%[^.]+\\.(.+)");

  @Test
  void hostIsLoopbackInEveryProfile() throws IOException {
    Map<String, String> hosts = new TreeMap<>();
    List<Path> files;
    try (Stream<Path> list = Files.list(RESOURCES)) {
      files =
          list.filter(p -> p.getFileName().toString().matches("application.*\\.properties"))
              .sorted()
              .toList();
    }
    for (Path file : files) {
      Properties properties = new Properties();
      try (Reader reader = Files.newBufferedReader(file)) {
        properties.load(reader);
      }
      for (String key : properties.stringPropertyNames()) {
        Matcher profiled = PROFILED.matcher(key);
        String unprofiled = profiled.matches() ? profiled.group(1) : key;
        if (unprofiled.equals(HOST)) {
          hosts.put(file.getFileName() + " " + key, properties.getProperty(key));
        }
      }
    }

    assertThat(files)
        .extracting(p -> p.getFileName().toString())
        .contains("application.properties");
    assertThat(hosts).containsEntry("application.properties " + HOST, "127.0.0.1");
    assertThat(hosts).allSatisfy((key, value) -> assertThat(value).as(key).isEqualTo("127.0.0.1"));
  }
}
