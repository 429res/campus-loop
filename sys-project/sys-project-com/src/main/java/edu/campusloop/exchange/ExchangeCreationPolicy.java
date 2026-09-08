package edu.campusloop.exchange;

import java.time.Duration;

/** Explicitly confirmed product policy. A-03 must apply it using database UTC inside its transaction. */
public final class ExchangeCreationPolicy {
    public static final String INITIAL_STATUS = "AWAITING_CONFIRMATION";
    public static final Duration CONFIRMATION_WINDOW = Duration.ofHours(24);
    public static final boolean INITIATOR_AUTO_CONFIRMS = false;
    private ExchangeCreationPolicy() {}
}
