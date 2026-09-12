package io.flottila.core;

import java.util.Map;

@FunctionalInterface
public interface TextStep {
    String apply(String in, Map<String, String> headers) throws Exception;
}
