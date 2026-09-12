package io.flottila.core;

/** Multipart attachment (mail, SOAP/MTOM, AS2/AS4). Stored by reference, not inlined into snapshots. */
public record Part(String name, String contentType, Body body) {}
