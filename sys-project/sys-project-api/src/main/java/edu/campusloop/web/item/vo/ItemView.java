package edu.campusloop.web.item.vo;
import java.time.LocalDateTime;
import java.util.List;
public record ItemView(long id,long ownerId,String ownerName,String title,String description,long categoryId,
    String categoryName,int conditionLevel,List<String> tags,long wantedCategoryId,String wantedCategoryName,
    List<String> wantedTags,String imageUrl,String status,int version,@com.fasterxml.jackson.annotation.JsonFormat(pattern="yyyy-MM-dd'T'HH:mm:ss'Z'") LocalDateTime createdAt,
    String reviewBasis,String reviewDecision,String reviewReason,String reviewedByName,
    @com.fasterxml.jackson.annotation.JsonFormat(pattern="yyyy-MM-dd'T'HH:mm:ss'Z'") LocalDateTime reviewedAt,Integer reviewedVersion,String ownerAvatarUrl) {}
