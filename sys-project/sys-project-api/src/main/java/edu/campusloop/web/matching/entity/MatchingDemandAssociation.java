package edu.campusloop.web.matching.entity;

import lombok.Data;

/** Read projection; descriptions and other private demand fields are intentionally absent. */
@Data
public class MatchingDemandAssociation {
    private Long demandId;
    private Long ownerId;
    private Long categoryId;
    private String preferredTagsJson;
    private String status;
    private Integer version;
    private Long itemId;
}
