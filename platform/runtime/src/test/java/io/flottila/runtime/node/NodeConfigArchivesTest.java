package io.flottila.runtime.node;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static io.flottila.runtime.node.NodeConfigTest.minimal;
import static io.flottila.runtime.node.NodeConfigTest.s3;
import static io.flottila.runtime.node.NodeConfigTest.with;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-09c3a (D-070), beside {@link NodeConfigTest}'s archive cases: the archive store and the S3 settings say one fact, whoever
 * builds the record; {@code FLOTTILA_S3_PATH_STYLE} is {@code true} or {@code false}; an endpoint names a host and a lower-case
 * http or https scheme (a deployed node's plain-http refusal compares the scheme as written).
 */
class NodeConfigArchivesTest {

    @Test
    void theArchiveStoreAndTheS3SettingsSayOneFact() {
        String secret = "secret-example-0815";
        NodeConfig dir = NodeConfig.from(minimal());
        NodeConfig s3 = NodeConfig.from(with(with(s3(), "FLOTTILA_S3_ACCESS_KEY", "AKIAEXAMPLE"), "FLOTTILA_S3_SECRET_KEY", secret));
        IllegalArgumentException dirWithS3 = assertThrows(IllegalArgumentException.class, () -> new NodeConfig(dir.nodeId(), dir.env(), dir.admin(),
                dir.dataDir(), dir.stopDeadline(), dir.maxPackageBytes(), dir.pg(), dir.blobs(), dir.archives(), s3.s3()), "a dir store with S3 settings");
        assertTrue(dirWithS3.getMessage().contains("FLOTTILA_ARCHIVE_STORE"), dirWithS3.getMessage());
        assertFalse(dirWithS3.getMessage().contains(secret), "the refusal shows the settings redacted: " + dirWithS3.getMessage());
        IllegalArgumentException s3Without = assertThrows(IllegalArgumentException.class, () -> new NodeConfig(s3.nodeId(), s3.env(), s3.admin(),
                s3.dataDir(), s3.stopDeadline(), s3.maxPackageBytes(), s3.pg(), s3.blobs(), s3.archives(), Optional.empty()), "an s3 store without them");
        assertTrue(s3Without.getMessage().contains("FLOTTILA_ARCHIVE_STORE"), s3Without.getMessage());
        NodeConfig same = new NodeConfig(s3.nodeId(), s3.env(), s3.admin(), s3.dataDir(), s3.stopDeadline(), s3.maxPackageBytes(), s3.pg(), s3.blobs(),
                s3.archives(), s3.s3());
        assertEquals(s3, same, "an s3 store with its settings is what from() reads");
    }

    @Test
    void s3PathStyleIsTrueOrFalse() {
        assertFalse(NodeConfig.from(with(s3(), "FLOTTILA_S3_PATH_STYLE", "false")).s3().orElseThrow().pathStyle(), "false, said");
        for (String bad : List.of("yes", "TRUE", "1", "", " true")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> NodeConfig.from(with(s3(), "FLOTTILA_S3_PATH_STYLE", bad)), bad);
            assertTrue(e.getMessage().contains("FLOTTILA_S3_PATH_STYLE"), "the refusal of '" + bad + "' names the key: " + e.getMessage());
        }
    }

    @Test
    void anS3EndpointNamesAHostAndALowerCaseScheme() {
        // http with no host: opaque, an empty authority, a port alone, a name no host may carry; HTTP: the refusal of plain http in
        // a deployed node compares the scheme as written, so an upper-case one is refused here, never let through there
        for (String bad : List.of("http:s3.example.net", "https:///archives", "http://:9000", "http://s3_x.example.net", "HTTP://s3.example.net")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> NodeConfig.from(with(s3(), "FLOTTILA_S3_ENDPOINT", bad)), bad);
            assertTrue(e.getMessage().contains("FLOTTILA_S3_ENDPOINT"), "the refusal of '" + bad + "' names the key: " + e.getMessage());
        }
    }
}
