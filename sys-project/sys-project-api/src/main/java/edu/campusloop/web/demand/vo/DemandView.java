package edu.campusloop.web.demand.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;
import java.util.List;

public record DemandView(long id, long ownerId, long categoryId, String categoryName,
                         String description, List<String> preferredTags, String status, int version,
                         @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'") LocalDateTime createdAt,
                         @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'") LocalDateTime updatedAt,
                         List<OfferedItemView> offeredItems, Long sourceItemId) {}
