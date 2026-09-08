package edu.campusloop.web.demand.vo;

public record OfferedItemView(long itemId, String title, Long categoryId, Integer conditionLevel,
                              String status, boolean offerable) {}
