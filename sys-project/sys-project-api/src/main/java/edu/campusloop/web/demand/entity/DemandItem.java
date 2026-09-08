package edu.campusloop.web.demand.entity;

import lombok.Data;

/** A candidate association; the existing item remains the sole ownership record. */
@Data
public class DemandItem {
    private Long demandId;
    private Long itemId;
}
