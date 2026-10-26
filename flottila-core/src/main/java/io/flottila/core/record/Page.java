package io.flottila.core.record;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One page of a bounded listing (D-057 a): a listing is never unlimited, so every store hands its rows out a page at a
 * time — at most {@link #MAX_LIMIT} items, and a cursor to the next page when another item exists. The cursor is opaque:
 * each store owns what it encodes (a position, never an offset), {@link #cursor} and {@link #position} only wrap it in
 * base64url.
 *
 * @param items the page's items, in the listing's order; a copy, never null elements
 * @param next where the next page starts; empty on the last page
 * @param <T> the item type
 * @since 0.1
 */
public record Page<T>(List<T> items, Optional<String> next) {

    /** The largest page a store hands out. */
    public static final int MAX_LIMIT = 500;

    public Page {
        items = List.copyOf(items);
        Objects.requireNonNull(next, "next");
    }

    /** {@code limit} when it is 1..{@link #MAX_LIMIT}; otherwise {@link IllegalArgumentException} (the admin API's 400). */
    public static int requireLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) throw new IllegalArgumentException("limit must be 1.." + MAX_LIMIT + ", was " + limit);
        return limit;
    }

    /** The opaque cursor of a store's position text. */
    public static String cursor(String position) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(position.getBytes(StandardCharsets.UTF_8));
    }

    /** The position text inside a cursor; {@link IllegalArgumentException} when it is not base64url. */
    public static String position(String cursor) {
        return new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
    }
}
