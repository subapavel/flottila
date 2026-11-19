package dev.rail8.mapping.studio;

import java.util.List;

/**
 * The {@code files-needed} problem: a {@link Problem} with the bundle references whose files a call
 * that sent none needs.
 *
 * @param type {@code urn:rail8:mapping:problem:files-needed}
 * @param title the slug's fixed summary
 * @param status 409
 * @param detail what the caller does next
 * @param references every reference the engine reads, once each, in the order it reads them
 */
record FilesNeeded(String type, String title, int status, String detail, List<String> references) {}
