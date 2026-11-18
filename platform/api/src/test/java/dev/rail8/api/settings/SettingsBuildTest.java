package dev.rail8.api.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A description is checked while it is built: every mistake an adapter author can make throws an
 * {@link IllegalArgumentException} whose message names the field, so a bad description refuses the node's start.
 */
class SettingsBuildTest {

    static void refused(Executable build, String expected) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, build);
        assertEquals(expected, e.getMessage());
    }

    @Test
    void aNameIsShortCamelCase() {
        assertDoesNotThrow(() -> Field.text("maxBodyBytes", "The limit."));
        refused(() -> Field.text("Max", "help"), "field 'Max': a name is a lower-case letter, then at most 63 letters or digits");
        refused(() -> Field.text("max-body", "help"), "field 'max-body': a name is a lower-case letter, then at most 63 letters or digits");
        refused(() -> Field.text("a".repeat(65), "help"), "field '" + "a".repeat(65) + "': a name is a lower-case letter, then at most 63 letters or digits");
    }

    @Test
    void everyFieldHasAHelpText() {
        refused(() -> Field.text("host", " "), "field 'host': a help text of 1 to 1000 characters");
        refused(() -> Field.text("host", "x".repeat(1001)), "field 'host': a help text of 1 to 1000 characters");
    }

    @Test
    void aDefaultFitsItsField() {
        refused(() -> Field.integer("port", "p").byDefault("22"), "field 'port': the default must be a whole number");
        refused(() -> Field.integer("port", "p").range(1, 10).byDefault(22), "field 'port': the default must be a whole number from 1 to 10");
        refused(() -> Field.integer("port", "p").byDefault(22).range(1, 10), "field 'port': the default must be a whole number from 1 to 10");
        refused(() -> Field.text("dir", "d").byDefault(""), "field 'dir': the default must be text that is not empty");
        refused(() -> Field.text("dir", "d").pattern("/.*").byDefault("in"), "field 'dir': the default must match /.*");
        refused(() -> Field.choice("mode", "m", "a", "b").byDefault("c"), "field 'mode': the default must be one of a, b");
        refused(() -> Field.bool("on", "o").byDefault("true"), "field 'on': the default must be true or false");
        refused(() -> Field.secret("password", "p").byDefault("s3cret"), "field 'password': a secret has no default");
        refused(() -> Field.text("user", "u").required().byDefault("me"), "field 'user': a required field has no default");
        refused(() -> Field.text("user", "u").byDefault("me").required(), "field 'user': a required field has no default");
    }

    @Test
    void limitsBelongToTheirType() {
        refused(() -> Field.text("dir", "d").min(1), "field 'dir': min and max are for whole numbers");
        refused(() -> Field.integer("port", "p").pattern("[0-9]+"), "field 'port': a pattern is for text");
        refused(() -> Field.integer("port", "p").range(10, 1), "field 'port': min 10 is above max 1");
        refused(() -> Field.text("dir", "d").pattern("("), "field 'dir': the pattern does not compile: (");
    }

    @Test
    void aChoiceHasDistinctValues() {
        refused(() -> Field.choice("mode", "m"), "field 'mode': a choice of 1 to 64 values, each 1 to 256 characters, none twice");
        refused(() -> Field.choice("mode", "m", "a", "a"), "field 'mode': a choice of 1 to 64 values, each 1 to 256 characters, none twice");
        refused(() -> Field.choice("mode", "m", ""), "field 'mode': a choice of 1 to 64 values, each 1 to 256 characters, none twice");
    }

    @Test
    void namesAreUnique() {
        refused(() -> Settings.of(Field.text("host", "h"), Field.text("host", "h").out()), "field 'host' is described twice");
    }

    @Test
    void theNodesKeysAreNoFieldOfAnInEndpoint() {
        refused(() -> Settings.of(Field.text("flow", "f")), "field 'flow': the node sets it on every in endpoint");
        refused(() -> Settings.of(Field.integer("maxBodyBytes", "m").in()), "field 'maxBodyBytes': the node sets it on every in endpoint");
        assertDoesNotThrow(() -> Settings.of(Field.integer("maxBodyBytes", "The reply's limit.").out()));
    }

    @Test
    void aVisibilityConditionNamesAnEarlierChoiceOrSwitch() {
        Field mode = Field.choice("mode", "m", "a", "b");
        refused(() -> Settings.of(Field.text("x", "x").visibleWhen("mode", "a"), mode), "field 'x': visible when 'mode', which is no field declared before it");
        refused(() -> Settings.of(Field.text("dir", "d"), Field.text("x", "x").visibleWhen("dir", "a")), "field 'x': visible when 'dir', which is neither a choice nor true or false");
        refused(() -> Settings.of(mode, Field.text("x", "x").visibleWhen("mode", "c")), "field 'x': visible when 'mode' is c, a value it never has");
        refused(() -> Settings.of(Field.bool("on", "o"), Field.text("x", "x").visibleWhen("on", "true")), "field 'x': visible when 'on' is true, a value it never has");
        refused(() -> Settings.of(mode.in(), Field.text("x", "x").visibleWhen("mode", "a")), "field 'x': visible when 'mode', which serves fewer directions");
        refused(() -> Field.text("x", "x").visibleWhen("mode"), "field 'x': visible when 'mode' is one of 1 to 64 values");
        assertDoesNotThrow(() -> Settings.of(mode, Field.bool("on", "o").visibleWhen("mode", "a", "b"), Field.text("x", "x").visibleWhen("on", true).in()));
    }

    @Test
    void anExactlyOneGroupHoldsPlainAlternatives() {
        Settings two = Settings.of(Field.secret("password", "p"), Field.secret("privateKey", "k"), Field.text("user", "u").required(),
                Field.choice("mode", "m", "a", "b"), Field.text("dir", "d").visibleWhen("mode", "a"), Field.text("url", "u").out(),
                Field.text("glob", "g").byDefault("*"));
        assertDoesNotThrow(() -> two.exactlyOne("password", "privateKey"));
        refused(() -> two.exactlyOne("password"), "exactly one of [password]: at least two fields, none twice");
        refused(() -> two.exactlyOne("password", "password"), "exactly one of [password, password]: at least two fields, none twice");
        refused(() -> two.exactlyOne("password", "pin"), "exactly one of [password, pin]: 'pin' is no field");
        refused(() -> two.exactlyOne("password", "user"), "exactly one of [password, user]: 'user' is required");
        refused(() -> two.exactlyOne("password", "glob"), "exactly one of [password, glob]: 'glob' has a default");
        refused(() -> two.exactlyOne("password", "dir"), "exactly one of [password, dir]: 'dir' is shown only sometimes");
        refused(() -> two.exactlyOne("password", "url"), "exactly one of [password, url]: 'url' serves other directions");
        refused(() -> two.exactlyOne("password", "privateKey").exactlyOne("privateKey", "url"), "exactly one of [privateKey, url]: 'privateKey' is in another group already");
    }

    @Test
    void anOwnTabIsNoCommonTab() {
        refused(() -> Tab.of("security", "Keys"), "tab 'security' is one of the platform's: general, retry, recording, security");
        refused(() -> Tab.of("Polling", "Polling"), "tab 'Polling': an id is a lower-case letter, then at most 31 lower-case letters, digits or dashes");
        refused(() -> Tab.of("polling", ""), "tab 'polling': a label of 1 to 64 characters");
        refused(() -> Settings.of(Field.text("a", "a").tab(Tab.of("files", "Files")), Field.text("b", "b").tab(Tab.of("files", "Folders"))),
                "tab 'files' has two labels: Files and Folders");
    }

    @Test
    void aDescriptionIsFinite() {
        List<Field> fields = new ArrayList<>();
        for (int i = 0; i < 257; i++) fields.add(Field.text("f" + i, "f"));
        refused(() -> Settings.of(fields.toArray(Field[]::new)), "a description holds at most 256 fields");
        assertDoesNotThrow(() -> Settings.of(fields.subList(0, 256).toArray(Field[]::new)));
        List<Field> tabbed = new ArrayList<>();
        for (int i = 0; i < 17; i++) tabbed.add(Field.text("f" + i, "f").tab(Tab.of("t" + i, "T")));
        refused(() -> Settings.of(tabbed.toArray(Field[]::new)), "a description adds at most 16 tabs of its own");
        Settings many = Settings.of(fields.subList(0, 40).toArray(Field[]::new));
        Settings grouped = many;
        for (int i = 0; i < 32; i += 2) grouped = grouped.exactlyOne("f" + i, "f" + (i + 1));
        Settings full = grouped;
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> full.exactlyOne("f32", "f33"));
        assertTrue(e.getMessage().contains("at most 16 groups"), e.getMessage());
    }
}
