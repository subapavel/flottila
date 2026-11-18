package dev.rail8.runtime.sample;

import dev.rail8.api.Cache;
import dev.rail8.api.Secret;
import dev.rail8.api.Stage;
import dev.rail8.api.StepConfig;
import dev.rail8.api.StepFactory;
import dev.rail8.api.StepResources;
import dev.rail8.api.plugin.Plugin;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;

/**
 * A sample of custom code: a plugin whose one step, {@code hmac-sign}, signs each message and contacts no one. It uses the four
 * parts of the API for custom code. It takes a secret named in its config ({@code Services.secret}). It keeps a derived key in
 * the cache ({@code Services.cache}). It reads the envelope's body and returns a new envelope with a header and a business
 * key. It logs through {@code System.Logger}.
 *
 * <p>Its config is {@code key}: {@code secret://<name>}, the signing secret. On each message the step takes the day's signing
 * key from the cache: HMAC-SHA256 of the secret over the date (UTC, on the node's clock), derived once a day. It signs the
 * body with that key and sets the header {@value HmacSign#HEADER} to the Base64 of the signature and the business key
 * {@value HmacSign#KEY} to the date. It logs one line at INFO.
 */
public final class HmacSignPlugin implements Plugin {

    @Override public String name() { return "hmac-sample"; }

    @Override public String api() { return API; }

    @Override public List<StepFactory> steps() { return List.of(new HmacSign()); }

    /** The step {@code hmac-sign}. */
    public static final class HmacSign implements StepFactory {

        public static final String TYPE = "hmac-sign";
        public static final String HEADER = "x-signature";
        public static final String KEY = "signed-on";

        private static final System.Logger LOG = System.getLogger(HmacSign.class.getName());

        @Override public String type() { return TYPE; }

        @Override public String configSchema() {
            return """
                    {"type":"object","required":["key"],"properties":{"key":{"type":"string","pattern":"^secret://"}}}""";
        }

        @Override
        public Stage create(StepConfig step, StepResources resources) {
            Secret secret = resources.services().secret(step, "key");   // checked now: a secret that is not set refuses the deploy
            Cache cache = resources.services().cache();
            Clock clock = resources.clock();
            return Stage.of(e -> {
                String day = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).toString();
                String dayKey = cache.get(secret.reference() + "@" + day, () -> new Cache.Entry(
                        Base64.getEncoder().encodeToString(hmac(secret.value().getBytes(StandardCharsets.UTF_8), day)), Duration.ofDays(1)));
                byte[] signature = hmac(Base64.getDecoder().decode(dayKey), e.body().whole().getBytes());
                LOG.log(System.Logger.Level.INFO, "signed {0} bytes with the key of {1}", e.body().size(), day);
                return e.withHeader(HEADER, Base64.getEncoder().encodeToString(signature)).withKey(KEY, day);
            });
        }

        /** HMAC-SHA256 of {@code data}, in UTF-8, under {@code key}. */
        public static byte[] hmac(byte[] key, String data) {
            return hmac(key, data.getBytes(StandardCharsets.UTF_8));
        }

        /** HMAC-SHA256 of {@code data} under {@code key}. */
        public static byte[] hmac(byte[] key, byte[] data) {
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(key, "HmacSHA256"));
                return mac.doFinal(data);
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("HmacSHA256 is part of every JDK", e);
            }
        }
    }
}
