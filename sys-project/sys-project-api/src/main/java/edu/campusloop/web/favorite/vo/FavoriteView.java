package edu.campusloop.web.favorite.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import edu.campusloop.web.item.vo.ItemView;
import java.time.LocalDateTime;

public record FavoriteView(long itemId,
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'") LocalDateTime favoritedAt,
    boolean itemVisible, ItemView item) {}
