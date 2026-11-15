package io.flottila.sim;

import java.util.List;

/** Every world a failure line or a pinned seed may name: the correct one and the broken variants of this tree. */
final class Worlds {

    private Worlds() {}

    static final List<String> NAMES = List.of("correct", AckBeforeSave.NAME, ReorderingFastPath.NAME, DoubleAdmission.NAME);

    /** A fresh world of that name (a variant keeps per-run state). */
    static World named(String name) {
        return switch (name) {
            case "correct" -> World.CORRECT;
            case AckBeforeSave.NAME -> new AckBeforeSave();
            case ReorderingFastPath.NAME -> new ReorderingFastPath();
            case DoubleAdmission.NAME -> new DoubleAdmission();
            case RenumberedIngress.NAME -> new RenumberedIngress();   // replayable, not pinned (P2-10a r2)
            case OverClaim.NAME -> new OverClaim();
            case KeyBlindClaim.NAME -> new KeyBlindClaim();
            default -> throw new IllegalArgumentException("no world named '" + name + "'; known: " + NAMES);
        };
    }
}
