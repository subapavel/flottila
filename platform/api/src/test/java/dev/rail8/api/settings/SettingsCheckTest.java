package dev.rail8.api.settings;

import dev.rail8.api.settings.Settings.Problem;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.rail8.api.settings.Direction.IN;
import static dev.rail8.api.settings.Direction.OUT;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What {@link Settings#check} says about an endpoint's options: one {@link Problem} per broken rule, at the option's JSON
 * pointer, in the form's words and never quoting a value.
 */
class SettingsCheckTest {

    static final Tab POLLING = Tab.of("polling", "Polling");

    /** A small adapter in the shape of SFTP's: a choice that shows a field, two alternatives, a pattern, a range. */
    static final Settings DROP = Settings.of(
                    Field.text("host", "The server."),
                    Field.integer("port", "The server's port.").range(1, 65535).byDefault(22),
                    Field.text("user", "The login.").required().tab(Tab.SECURITY),
                    Field.secret("password", "The password, or set privateKey.").tab(Tab.SECURITY),
                    Field.secret("privateKey", "The private key, or set password.").tab(Tab.SECURITY),
                    Field.text("hostKey", "The server key's fingerprint.").required().pattern("SHA256:[A-Za-z0-9+/]+=*").tab(Tab.SECURITY),
                    Field.choice("afterSuccess", "What happens to a delivered file.", "delete", "move").byDefault("delete").tab(POLLING).in(),
                    Field.text("moveTo", "Where a delivered file goes.").required().visibleWhen("afterSuccess", "move").tab(POLLING).in(),
                    Field.bool("archive", "Whether a moved file is also archived.").visibleWhen("afterSuccess", "move").tab(POLLING).in(),
                    Field.text("archiveDir", "Where archived files go.").required().visibleWhen("archive", true).tab(POLLING).in(),
                    Field.text("url", "Where a producer sends.").out())
            .exactlyOne("password", "privateKey");

    static final Map<String, Object> VALID = Map.of("user", "rail8", "password", "s3cret", "hostKey", "SHA256:zuCbj2cX0buHOs3U");

    static Map<String, Object> with(Map<String, Object> options, Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>(options);
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
        return m;
    }

    static Map<String, Object> without(Map<String, Object> options, String key) {
        Map<String, Object> m = new LinkedHashMap<>(options);
        m.remove(key);
        return m;
    }

    static List<Problem> problems(Problem... expected) {
        return List.of(expected);
    }

    @Test
    void validOptionsHaveNoProblem() {
        assertEquals(List.of(), DROP.check(VALID, IN));
        assertEquals(List.of(), DROP.check(with(VALID, "host", "sftp", "port", 2222, "afterSuccess", "move", "moveTo", "/done"), IN));
        assertEquals(List.of(), DROP.check(with(without(VALID, "password"), "privateKey", "-----BEGIN KEY-----"), IN));
    }

    @Test
    void aRequiredFieldLeftOutIsNamed() {
        assertEquals(problems(new Problem("/user", "is required")), DROP.check(without(VALID, "user"), IN));
    }

    @Test
    void aFieldShownByAnotherIsRequiredOnlyWhileShown() {
        assertEquals(problems(new Problem("/moveTo", "is required when afterSuccess is move")), DROP.check(with(VALID, "afterSuccess", "move"), IN));
        assertEquals(List.of(), DROP.check(with(VALID, "afterSuccess", "delete"), IN), "hidden: not required");
        assertEquals(List.of(), DROP.check(VALID, IN), "the controller's default, delete, hides it");
    }

    @Test
    void visibilityIsTransitive() {
        Map<String, Object> archived = with(VALID, "archive", true);
        assertEquals(List.of(), DROP.check(archived, IN), "archive is hidden while afterSuccess is delete, so archiveDir is hidden too");
        assertEquals(problems(new Problem("/archiveDir", "is required when archive is true")),
                DROP.check(with(archived, "afterSuccess", "move", "moveTo", "/done"), IN));
    }

    @Test
    void aHiddenFieldMayBeSetButIsStillChecked() {
        assertEquals(List.of(), DROP.check(with(VALID, "moveTo", "/done"), IN), "set while hidden: not refused");
        assertEquals(problems(new Problem("/moveTo", "must be text")), DROP.check(with(VALID, "moveTo", 7), IN));
    }

    @Test
    void anUnknownKeyIsRefused() {
        assertEquals(problems(new Problem("/colour", "no such setting"), new Problem("/pasword", "no such setting")),
                DROP.check(with(VALID, "pasword", "typo", "colour", "red"), IN), "unknown keys in name order");
    }

    @Test
    void aKeyOfTheOtherDirectionIsRefused() {
        assertEquals(problems(new Problem("/url", "a setting of out endpoints only")), DROP.check(with(VALID, "url", "http://x"), IN));
        assertEquals(problems(new Problem("/afterSuccess", "a setting of in endpoints only")), DROP.check(with(VALID, "afterSuccess", "move"), OUT));
    }

    @Test
    void theKeysTheNodeSetsOnAnInEndpointAreNotTheDescriptionsBusiness() {
        assertEquals(List.of(), DROP.check(with(VALID, "flow", "orders/intake", "maxBodyBytes", 1_000_000), IN));
        assertEquals(problems(new Problem("/flow", "no such setting"), new Problem("/maxBodyBytes", "no such setting")),
                DROP.check(with(VALID, "flow", "orders/intake", "maxBodyBytes", 1_000_000), OUT), "on an out endpoint the node sets neither");
        Settings replies = Settings.of(Field.integer("maxBodyBytes", "The reply's limit.").required().min(1).out());
        assertEquals(List.of(), replies.check(Map.of("maxBodyBytes", 1_000_000, "flow", "orders/intake"), IN), "an out-only field of that name is skipped on in");
        assertEquals(problems(new Problem("/maxBodyBytes", "is required")), replies.check(Map.of(), OUT));
    }

    @Test
    void exactlyOneOfAGroup() {
        assertEquals(problems(new Problem("", "set exactly one of password, privateKey")), DROP.check(without(VALID, "password"), IN));
        assertEquals(problems(new Problem("", "set exactly one of password, privateKey")), DROP.check(with(VALID, "privateKey", "k"), IN));
    }

    @Test
    void typesAreChecked() {
        assertEquals(problems(new Problem("/host", "must be text")), DROP.check(with(VALID, "host", 7), IN));
        assertEquals(problems(new Problem("/port", "must be a whole number from 1 to 65535")), DROP.check(with(VALID, "port", "22"), IN));
        assertEquals(problems(new Problem("/port", "must be a whole number from 1 to 65535")), DROP.check(with(VALID, "port", 2.5), IN));
        assertEquals(problems(new Problem("/archive", "must be true or false")), DROP.check(with(VALID, "archive", "yes"), IN));
        assertEquals(problems(new Problem("/afterSuccess", "must be one of delete, move")), DROP.check(with(VALID, "afterSuccess", "copy"), IN));
        assertEquals(problems(new Problem("/password", "must be text")), DROP.check(with(VALID, "password", List.of("s3cret")), IN));
    }

    @Test
    void wholeNumbersAreCheckedAgainstTheirRange() {
        assertEquals(List.of(), DROP.check(with(VALID, "port", 1), IN));
        assertEquals(List.of(), DROP.check(with(VALID, "port", 65535L), IN));
        assertEquals(List.of(), DROP.check(with(VALID, "port", 22.0), IN), "a whole number written with a fraction of zero");
        assertEquals(List.of(), DROP.check(with(VALID, "port", BigInteger.valueOf(22)), IN));
        for (Object out : List.of(0, 65536, -1, Long.MAX_VALUE, new BigInteger("100000000000000000000"))) {
            assertEquals(problems(new Problem("/port", "must be a whole number from 1 to 65535")), DROP.check(with(VALID, "port", out), IN), "port " + out);
        }
        Settings bounds = Settings.of(Field.integer("least", "At least 1.").min(1), Field.integer("most", "At most 9.").max(9),
                Field.integer("any", "Any whole number."));
        assertEquals(problems(new Problem("/least", "must be a whole number of at least 1"), new Problem("/most", "must be a whole number of at most 9"),
                new Problem("/any", "must be a whole number")), bounds.check(Map.of("least", 0, "most", 10, "any", 1.5), IN));
    }

    @Test
    void anEmptyStringIsNoValue() {
        assertEquals(problems(new Problem("/host", "must not be empty")), DROP.check(with(VALID, "host", ""), IN));
        assertEquals(problems(new Problem("/user", "must not be empty")), DROP.check(with(VALID, "user", ""), IN));
        assertEquals(problems(new Problem("/password", "must not be empty")), DROP.check(with(VALID, "password", ""), IN));
    }

    @Test
    void nullIsAbsent() {
        Map<String, Object> options = new HashMap<>(VALID);
        options.put("host", null);
        assertEquals(List.of(), DROP.check(options, IN));
        options.put("user", null);
        assertEquals(problems(new Problem("/user", "is required")), DROP.check(options, IN));
    }

    @Test
    void aPatternMatchesTheWholeValue() {
        assertEquals(problems(new Problem("/hostKey", "must match SHA256:[A-Za-z0-9+/]+=*")), DROP.check(with(VALID, "hostKey", "sha256:abc"), IN));
        assertEquals(problems(new Problem("/hostKey", "must match SHA256:[A-Za-z0-9+/]+=*")), DROP.check(with(VALID, "hostKey", "x SHA256:abc"), IN));
    }

    @Test
    void aSecretReferenceStandsInForAnyTextAndIsResolvedLater() {
        assertEquals(List.of(), DROP.check(with(VALID, "hostKey", "secret://sftp-host-key", "password", "secret://sftp.password_2"), IN),
                "the pattern is not asked of a reference");
        assertEquals(problems(new Problem("/hostKey", "must match SHA256:[A-Za-z0-9+/]+=*")), DROP.check(with(VALID, "hostKey", "secret://UPPER"), IN),
                "a malformed reference is plain text");
        assertEquals(problems(new Problem("/port", "must be a whole number from 1 to 65535")), DROP.check(with(VALID, "port", "secret://port"), IN),
                "only text and secret settings take a reference");
    }

    @Test
    void problemsComeFieldsFirstThenUnknownKeysThenGroups() {
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("zebra", 1);
        bad.put("hostKey", "nope");
        bad.put("port", 0);
        assertEquals(problems(new Problem("/port", "must be a whole number from 1 to 65535"), new Problem("/user", "is required"),
                new Problem("/hostKey", "must match SHA256:[A-Za-z0-9+/]+=*"), new Problem("/zebra", "no such setting"),
                new Problem("", "set exactly one of password, privateKey")), DROP.check(bad, IN));
    }

    @Test
    void aKeyIsAJsonPointer() {
        assertEquals(problems(new Problem("/a~1b~0c", "no such setting")), DROP.check(with(VALID, "a/b~c", 1), IN));
    }

    @Test
    void aDescriptionWithNoFieldsTakesNoOption() {
        assertEquals(List.of(), Settings.of().check(Map.of(), OUT));
        assertEquals(problems(new Problem("/url", "no such setting")), Settings.of().check(Map.of("url", "x"), OUT));
    }

    @Test
    void anUndescribedComponentIsNotJudgedHere() {
        assertEquals(List.of(), Settings.NONE.check(Map.of("anything", 1), IN));
    }
}
