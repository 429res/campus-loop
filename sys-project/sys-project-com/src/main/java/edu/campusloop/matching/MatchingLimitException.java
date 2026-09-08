package edu.campusloop.matching;

/** A complete recommendation result cannot be computed within the supported matching limits. */
public final class MatchingLimitException extends RuntimeException {
    public MatchingLimitException(String message) {
        super(message);
    }
}
