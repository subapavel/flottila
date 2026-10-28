package io.flottila.runtime.node;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ServiceClientConfiguration;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static io.flottila.runtime.node.NodeConfigTest.s3;
import static io.flottila.runtime.node.NodeConfigTest.with;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * P1-09c3a (D-070): the node's one S3 client takes the region, the endpoint when one is given (else the SDK's for the region) and
 * the static keys when both are given (else the SDK's default credentials chain). Building it contacts nothing.
 */
class NodeS3ClientTest {

    /** Builds the node's client from {@code env}, hands its configuration to {@code check}, closes it. */
    static void built(Map<String, String> env, Consumer<S3ServiceClientConfiguration> check) {
        try (S3Client client = Node.s3Client(NodeConfig.from(env).s3().orElseThrow())) {
            check.accept(client.serviceClientConfiguration());
        }
    }

    @Test
    void withoutKeysOrAnEndpointTheClientTakesTheSdkDefaults() {
        built(s3(), c -> {
            assertEquals(Region.EU_CENTRAL_1, c.region(), "FLOTTILA_S3_REGION");
            assertEquals(Optional.empty(), c.endpointOverride(), "no endpoint: the SDK's for the region");
            assertInstanceOf(DefaultCredentialsProvider.class, c.credentialsProvider(), "no keys: the SDK's default credentials chain");
        });
    }

    @Test
    void givenKeysAndAnEndpointTheClientUsesThem() {
        Map<String, String> env = with(with(with(s3(), "FLOTTILA_S3_ENDPOINT", "https://s3.example.net:9000"), "FLOTTILA_S3_ACCESS_KEY", "AKIAEXAMPLE"),
                "FLOTTILA_S3_SECRET_KEY", "secret-example");
        built(env, c -> {
            assertEquals(Optional.of(URI.create("https://s3.example.net:9000")), c.endpointOverride());
            StaticCredentialsProvider keys = assertInstanceOf(StaticCredentialsProvider.class, c.credentialsProvider(), "both keys: static credentials");
            AwsCredentials credentials = keys.resolveCredentials();
            assertEquals("AKIAEXAMPLE", credentials.accessKeyId());
            assertEquals("secret-example", credentials.secretAccessKey());
        });
    }
}
