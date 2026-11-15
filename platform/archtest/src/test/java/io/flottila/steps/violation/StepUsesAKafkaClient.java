package io.flottila.steps.violation;

/** Deliberate violation: a step touches a Kafka client package (only runtime may). */
public final class StepUsesAKafkaClient {
    private StepUsesAKafkaClient() {}
    public static Class<?> client() { return io.vertx.kafka.client.producer.KafkaProducer.class; }
}
