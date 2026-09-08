package edu.campusloop.web.admin.vo;

/** An unavailable recommendation count is null, never a fabricated zero. */
public record AdminStatsView(long users, long items, long availableItems,
                             Long recommendations, RecommendationStatus recommendationsStatus) {
    public enum RecommendationStatus { AVAILABLE, LIMIT_EXCEEDED }
}
