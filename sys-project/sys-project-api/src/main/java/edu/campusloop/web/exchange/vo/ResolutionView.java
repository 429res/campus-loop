package edu.campusloop.web.exchange.vo;
import java.time.LocalDateTime;
public record ResolutionView(long id,long exchangeId,long actorUserId,String decision,String reason,boolean returnConfirmed,int previousVersion,int newVersion,@com.fasterxml.jackson.annotation.JsonFormat(pattern="yyyy-MM-dd'T'HH:mm:ss'Z'") LocalDateTime createdAt) {}
