package io.flottila.steps.validate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * CORE-9 (O-1, CORE-GATE condition 6): Kafka is an optional add-on, not in this build. No Kafka client reaches flottila-step-validate, not even
 * at test scope: neither kafka-clients nor vertx-kafka-client resolves through this module's test class loader. (flottila-archtest
 * keeps vertx-kafka-client at test scope on purpose: its ArchUnit violation fixture points at it.)
 */
class NoKafkaClientTest {

    /** One class of each Kafka client jar: kafka-clients (producer, consumer) and vertx-kafka-client (producer, consumer). */
    private static final List<String> KAFKA_CLIENT_CLASSES = List.of(
            "org.apache.kafka.clients.producer.Producer",
            "org.apache.kafka.clients.consumer.Consumer",
            "io.vertx.kafka.client.producer.KafkaProducer",
            "io.vertx.kafka.client.consumer.KafkaConsumer");

    @Test
    void noKafkaClientClassIsLoadableOnThisModulesTestClasspath() {
        ClassLoader loader = NoKafkaClientTest.class.getClassLoader();
        // witness (not vacuous): the same loader, asked the same way, resolves this module's own code and a Vert.x class
        assertDoesNotThrow(() -> Class.forName("io.flottila.steps.validate.XsdStepFactory", false, loader), "the loader sees the module's production classes");
        assertDoesNotThrow(() -> Class.forName("io.vertx.core.Future", false, loader), "the loader sees the module's Vert.x dependency");
        assertAll(KAFKA_CLIENT_CLASSES.stream().map(name -> (Executable) () -> assertThrows(ClassNotFoundException.class,
                () -> Class.forName(name, false, loader), name + " is loadable on the test classpath of flottila-step-validate: a Kafka client jar reached it")));
    }
}
