package io.flottila.mapping.service;

import static io.flottila.mapping.service.Fixtures.CORPUS_MAPPING_ETAG;
import static io.flottila.mapping.service.Fixtures.NEW_MAPPING_ETAG;
import static io.flottila.mapping.service.Fixtures.ORDER;
import static io.flottila.mapping.service.Fixtures.UNWIRED_ETAG;
import static io.flottila.mapping.service.Fixtures.bytes;
import static io.flottila.mapping.service.Fixtures.corpusMapping;
import static io.flottila.mapping.service.Fixtures.workingCopy;
import static io.flottila.mapping.service.Wire.createRequest;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import org.junit.jupiter.api.Test;

/**
 * The ETag is {@code "sha256:<64 lowercase hex>"}, a strong tag over exactly the canonical bytes
 * the response carries (ADR-0024). Expected tags are {@code sha256sum} of the expected bodies.
 */
@QuarkusTest
class EtagTest {

  private static final String STRONG_SHA256 = "\"sha256:[0-9a-f]{64}\"";

  @TestHTTPResource URI root;

  @Test
  void etagIsQuotedSha256OfTheBody() {
    Http http = new Http(root);

    // a stored mapping
    Http.Reply get = http.get("api/mappings/mappings/order-id-copy");
    assertThat(get.header("ETag")).hasValueSatisfying(t -> assertThat(t).matches(STRONG_SHA256));
    assertThat(get.header("ETag")).contains(CORPUS_MAPPING_ETAG);

    // a save: the tag of the canonical bytes answered, not of the request's
    TempWorkspace.write("etag/order.mapping.json", corpusMapping());
    Http.Reply put =
        http.put("api/mappings/etag/order", "application/json", bytes(workingCopy("null")));
    assertThat(put.status()).as("%s", put).isEqualTo(200);
    assertThat(put.header("ETag")).contains(UNWIRED_ETAG);

    // a create
    TempWorkspace.mkdirs("etag-created");
    Http.Reply created =
        http.post(
            "api/mappings",
            "application/json",
            bytes(createRequest("etag-created/order", "../structures/skeleton/order.xsd", ORDER)));
    assertThat(created.status()).as("%s", created).isEqualTo(201);
    assertThat(created.header("ETag")).contains(NEW_MAPPING_ETAG);
  }
}
