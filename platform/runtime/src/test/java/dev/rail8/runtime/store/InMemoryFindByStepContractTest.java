package dev.rail8.runtime.store;

import dev.rail8.core.record.InMemoryMessageStore;
import dev.rail8.core.record.MessageStore;

/** Core's in-memory message store answers the search by step as every store does. */
class InMemoryFindByStepContractTest extends FindByStepContract {

    @Override
    protected MessageStore emptyStore() {
        return new InMemoryMessageStore();
    }
}
