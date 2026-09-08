package edu.campusloop.web.exchange.entity;

import edu.campusloop.web.item.entity.Item;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class ExchangeCandidateItem extends Item {
    private String ownerName;
    private String userStatus;
    private String categoryName;
    private boolean blocked;
}
