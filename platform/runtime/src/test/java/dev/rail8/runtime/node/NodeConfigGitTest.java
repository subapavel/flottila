package dev.rail8.runtime.node;

import dev.rail8.runtime.git.GitSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static dev.rail8.runtime.node.NodeConfigTest.minimal;
import static dev.rail8.runtime.node.NodeConfigTest.with;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code RAIL8_GIT_*} keys: where the pool's package repositories are and how they are read. No base, no repositories. An
 * {@code https://} base in every environment, a {@code file://} one in dev and ci only, never one with user information, and no
 * refusal repeats the base. A deployed environment that sets a base sets its ref. The user and the token come together, for an
 * https base, and the configuration's string never shows the token.
 */
class NodeConfigGitTest {

    private static final String TOKEN = "glpat-Secret-Token-4711";

    private static Map<String, String> deployed() {
        Map<String, String> env = with(with(minimal(), "RAIL8_ENV", "prod"), "RAIL8_JOURNAL_COPIES", "1");
        return with(env, "RAIL8_GIT_REF", "refs/heads/release");
    }

    private static IllegalArgumentException refused(Map<String, String> env) {
        return assertThrows(IllegalArgumentException.class, () -> NodeConfig.from(env));
    }

    @Test
    void withoutABaseTheNodeHasNoRepositories() {
        assertEquals(Optional.empty(), NodeConfig.from(minimal()).git());
        assertEquals(Optional.empty(), NodeConfig.from(with(minimal(), "RAIL8_GIT_PATH", "examples/demo")).git());
    }

    @Test
    void theKeysAreReadUnderTheirNamesWithTheirDefaults() {
        GitSettings git = NodeConfig.from(with(minimal(), "RAIL8_GIT_BASE", "https://gitlab.example.com/integration")).git().orElseThrow();
        assertEquals(new GitSettings(URI.create("https://gitlab.example.com/integration/")), git, "a base is given its /");
        assertEquals(Duration.ofSeconds(60), git.timeout());
        assertEquals(1L << 30, git.maxBytes());

        Map<String, String> env = with(deployed(), "RAIL8_GIT_BASE", "https://gitlab.example.com/integration/");
        with(env, "RAIL8_GIT_PATH", "examples/demo");
        with(env, "RAIL8_GIT_USER", "rail8-reader");
        with(env, "RAIL8_GIT_TOKEN", TOKEN);
        with(env, "RAIL8_GIT_TIMEOUT", "PT2M");
        with(env, "RAIL8_GIT_MAX_BYTES", String.valueOf(2L << 30));
        git = NodeConfig.from(env).git().orElseThrow();
        assertEquals("examples/demo", git.path());
        assertEquals(Optional.of("refs/heads/release"), git.ref());
        assertEquals(Optional.of(new GitSettings.Credentials("rail8-reader", TOKEN)), git.credentials());
        assertEquals(Duration.ofMinutes(2), git.timeout());
        assertEquals(2L << 30, git.maxBytes());
    }

    @Test
    void theStringOfTheConfigurationNeverShowsTheToken() {
        Map<String, String> env = with(deployed(), "RAIL8_GIT_BASE", "https://gitlab.example.com/integration/");
        with(env, "RAIL8_GIT_USER", "rail8-reader");
        with(env, "RAIL8_GIT_TOKEN", TOKEN);
        NodeConfig config = NodeConfig.from(env);
        assertFalse(config.toString().contains(TOKEN), config.toString());
        assertTrue(config.toString().contains("rail8-reader"), config.toString());
        assertFalse(config.withSecrets(config.secrets()).toString().contains(TOKEN));
        assertEquals(config.git(), config.withSecrets(config.secrets()).git(), "withSecrets keeps the repositories");
    }

    @ParameterizedTest(name = "RAIL8_GIT_BASE={0}")
    @ValueSource(strings = {"https://rail8:" + TOKEN + "@gitlab.example.com/integration/", "https://" + TOKEN + "@gitlab.example.com/"})
    void aBaseWithUserInformationIsRefusedWithoutRepeatingIt(String base) {
        IllegalArgumentException refused = refused(with(minimal(), "RAIL8_GIT_BASE", base));
        assertTrue(refused.getMessage().startsWith("RAIL8_GIT_BASE is refused: it carries user information"), refused.getMessage());
        assertFalse(refused.getMessage().contains(TOKEN), refused.getMessage());
        assertFalse(refused.getMessage().contains("gitlab.example.com"), refused.getMessage());
    }

    @ParameterizedTest(name = "RAIL8_ENV={0}, RAIL8_GIT_BASE={1}")
    @CsvSource({
            "ci, http://gitlab.example.com/integration/",
            "ci, ssh://git@gitlab.example.com/integration/",
            "ci, gitlab.example.com/integration/",
            "ci, https:///integration/",
            "ci, https://gitlab.example.com/integration/?private=" + TOKEN,
            "ci, 'https://gitlab.example.com/integration/ x'",
            "prod, file:///workspace/",
    })
    void aBaseOutsideItsSchemesIsRefusedWithoutRepeatingIt(String environment, String base) {
        Map<String, String> env = with(with(deployed(), "RAIL8_ENV", environment), "RAIL8_GIT_BASE", base);
        IllegalArgumentException refused = refused(env);
        assertTrue(refused.getMessage().startsWith("RAIL8_GIT_BASE is refused: "), refused.getMessage());
        assertTrue(refused.getMessage().endsWith("(the value is not shown)"), refused.getMessage());
        assertFalse(refused.getMessage().contains("example.com") || refused.getMessage().contains("workspace"), refused.getMessage());
    }

    @Test
    void aFileBaseServesDevAndCi() {
        for (String environment : new String[] {"dev", "ci"}) {
            GitSettings git = NodeConfig.from(with(with(minimal(), "RAIL8_ENV", environment), "RAIL8_GIT_BASE", "file:///workspace")).git().orElseThrow();
            assertEquals(URI.create("file:///workspace/"), git.base());
            assertTrue(git.local());
        }
    }

    @Test
    void aDeployedEnvironmentThatSetsABaseSetsItsRef() {
        Map<String, String> env = with(with(deployed(), "RAIL8_GIT_BASE", "https://gitlab.example.com/integration/"), "RAIL8_GIT_REF", null);
        IllegalArgumentException refused = refused(env);
        assertTrue(refused.getMessage().startsWith("RAIL8_GIT_REF must be set in a deployed environment that sets RAIL8_GIT_BASE"), refused.getMessage());
        assertEquals(Optional.empty(), NodeConfig.from(with(minimal(), "RAIL8_GIT_BASE", "https://gitlab.example.com/")).git().orElseThrow().ref(),
                "optional in dev and ci");
    }

    @ParameterizedTest(name = "RAIL8_GIT_REF={0}")
    @ValueSource(strings = {"main", "release", "refs/remotes/origin/main", "refs/heads/bad..name", "refs/heads/"})
    void aRefThatIsNoFullBranchOrTagNameIsRefused(String ref) {
        Map<String, String> env = with(with(minimal(), "RAIL8_GIT_BASE", "https://gitlab.example.com/"), "RAIL8_GIT_REF", ref);
        assertTrue(refused(env).getMessage().startsWith("RAIL8_GIT_REF must be a full branch or tag name"));
    }

    @ParameterizedTest(name = "RAIL8_GIT_PATH={0}")
    @ValueSource(strings = {"/examples", "examples/", "../demo", "examples/../demo", "examples//demo", "./demo", "examples\\demo"})
    void aPathThatIsNotARelativeFolderIsRefused(String path) {
        Map<String, String> env = with(with(minimal(), "RAIL8_GIT_BASE", "https://gitlab.example.com/"), "RAIL8_GIT_PATH", path);
        assertTrue(refused(env).getMessage().startsWith("RAIL8_GIT_PATH must be a relative folder"));
    }

    @Test
    void theUserAndTheTokenComeTogetherForAnHttpsBase() {
        Map<String, String> https = with(minimal(), "RAIL8_GIT_BASE", "https://gitlab.example.com/");
        IllegalArgumentException alone = refused(with(new java.util.HashMap<>(https), "RAIL8_GIT_TOKEN", TOKEN));
        assertEquals("RAIL8_GIT_USER and RAIL8_GIT_TOKEN are given both or neither", alone.getMessage());
        refused(with(new java.util.HashMap<>(https), "RAIL8_GIT_USER", "rail8-reader"));
        IllegalArgumentException blank = refused(with(with(new java.util.HashMap<>(https), "RAIL8_GIT_USER", "rail8-reader"), "RAIL8_GIT_TOKEN", " "));
        assertEquals("RAIL8_GIT_TOKEN must not be blank", blank.getMessage());

        Map<String, String> file = with(with(with(minimal(), "RAIL8_GIT_BASE", "file:///workspace/"), "RAIL8_GIT_USER", "rail8-reader"), "RAIL8_GIT_TOKEN", TOKEN);
        IllegalArgumentException local = refused(file);
        assertTrue(local.getMessage().contains("https"), local.getMessage());
        assertFalse(local.getMessage().contains(TOKEN));
    }

    @ParameterizedTest(name = "{0}={1}")
    @CsvSource({
            "RAIL8_GIT_TIMEOUT, PT0.5S",
            "RAIL8_GIT_TIMEOUT, PT11M",
            "RAIL8_GIT_TIMEOUT, sixty",
            "RAIL8_GIT_MAX_BYTES, 1048575",
            "RAIL8_GIT_MAX_BYTES, 68719476737",
            "RAIL8_GIT_MAX_BYTES, lots",
    })
    void aTimeoutOrACacheSizeOutOfItsRangeIsRefusedNamingTheKey(String key, String value) {
        Map<String, String> env = with(with(minimal(), "RAIL8_GIT_BASE", "https://gitlab.example.com/"), key, value);
        assertTrue(refused(env).getMessage().startsWith(key), key);
    }

    @Test
    void bothBoundsOfTheTimeoutAndTheCacheSizeAreAccepted() {
        Map<String, String> low = with(with(with(minimal(), "RAIL8_GIT_BASE", "https://gitlab.example.com/"), "RAIL8_GIT_TIMEOUT", "PT1S"),
                "RAIL8_GIT_MAX_BYTES", String.valueOf(1L << 20));
        assertEquals(Duration.ofSeconds(1), NodeConfig.from(low).git().orElseThrow().timeout());
        Map<String, String> high = with(with(with(minimal(), "RAIL8_GIT_BASE", "https://gitlab.example.com/"), "RAIL8_GIT_TIMEOUT", "PT10M"),
                "RAIL8_GIT_MAX_BYTES", String.valueOf(64L << 30));
        assertEquals(64L << 30, NodeConfig.from(high).git().orElseThrow().maxBytes());
    }
}
