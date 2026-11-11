package io.flottila.runtime;

import java.util.Map;

/**
 * CORE-17 P1 (D-130 draft): the body journal's segment rows are the intake pool's. {@link NodePoolRolesIT}'s cases on a journal
 * whose segments hold one record each ({@code FLOTTILA_JOURNAL_SEGMENT_RECORDS} 1), so every save's body opens a segment whose
 * row is inserted while the work pool is saturated: {@link NodePoolRolesIT#aSaturatedWorkPoolDoesNotDelayASave}'s probe is
 * accepted only when those rows do not wait for a work pool connection.
 */
class NodePoolRolesJournalRowsIT extends NodePoolRolesIT {

    @Override
    Map<String, String> env() throws Exception {
        Map<String, String> env = super.env();
        env.put("FLOTTILA_JOURNAL_SEGMENT_RECORDS", "1");   // a segment, and its row, per record
        return env;
    }
}
