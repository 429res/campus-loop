package edu.campusloop.web.item.service;

import java.util.Set;

/** Public visibility is shared by browsing, detail and bookmarks. It is not exchange eligibility. */
public final class ItemVisibility {
    public static final Set<String> PUBLIC_STATES = Set.of("AVAILABLE", "RESERVED", "EXCHANGED");
    private ItemVisibility() {}
}
