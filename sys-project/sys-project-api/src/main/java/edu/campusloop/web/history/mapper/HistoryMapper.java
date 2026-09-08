package edu.campusloop.web.history.mapper;
import edu.campusloop.web.history.entity.HistoryEvent;
import org.apache.ibatis.annotations.*;
import java.util.List;

/** Deliberately append-only: no update/delete mapper for historical facts. */
public interface HistoryMapper {
    String PAIR="EXISTS (SELECT 1 FROM cl_exchange_participant p JOIN cl_exchange e ON e.id=p.exchange_id WHERE e.status='COMPLETED' AND p.exchange_id=h.exchange_id AND p.offered_item_id=h.item_id AND (p.user_id=#{actor} OR p.recipient_user_id=#{actor}))";
    String VISIBLE="(#{all}=TRUE OR h.source_user_id=#{actor} OR "+PAIR+")";
    String COLUMNS="h.*,u.display_name AS author_display_name,(SELECT c.id FROM cl_item_history c WHERE c.corrects_event_id=h.id) AS corrected_by_event_id";
    String FROM=" FROM cl_item_history h JOIN cl_user u ON u.id=h.source_user_id ";
    @Select("SELECT COUNT(*)"+FROM+"WHERE h.item_id=#{item} AND "+VISIBLE)
    long count(@Param("item") long item,@Param("actor") long actor,@Param("all") boolean all);
    @Select("SELECT "+COLUMNS+FROM+"WHERE h.item_id=#{item} AND "+VISIBLE+" ORDER BY h.recorded_at DESC,h.id DESC LIMIT #{size} OFFSET #{offset}")
    List<HistoryEvent> page(@Param("item") long item,@Param("actor") long actor,@Param("all") boolean all,@Param("size") int size,@Param("offset") long offset);
    @Select("SELECT "+COLUMNS+FROM+"WHERE h.id=#{id}")
    HistoryEvent find(long id);
    @Select("SELECT COUNT(*) FROM cl_item_history h WHERE h.id=#{id} AND (h.source_user_id=#{actor} OR "+PAIR+")")
    int related(@Param("id") long id,@Param("actor") long actor);
    String TRANSFER="SELECT h.* FROM cl_item_history h JOIN cl_exchange e ON e.id=h.exchange_id JOIN cl_exchange_participant p ON p.exchange_id=h.exchange_id AND p.offered_item_id=h.item_id AND p.user_id=h.source_user_id AND p.recipient_user_id=h.counterparty_user_id WHERE h.item_id=#{item} AND e.status='COMPLETED' AND e.rule_version='independent-v2' AND e.creation_snapshot IS NOT NULL AND h.event_type='EXCHANGED' AND h.evidence_level='BOTH_CONFIRMED' AND h.confirmed_at IS NOT NULL ";
    @Select(TRANSFER+"AND h.exchange_id=#{exchange}")
    HistoryEvent transfer(@Param("item") long item,@Param("exchange") long exchange);
    @Select(TRANSFER+"ORDER BY h.id DESC LIMIT 1")
    HistoryEvent latestTransfer(long item);
    @Select(TRANSFER+"AND h.id < #{before} ORDER BY h.id DESC LIMIT 1")
    HistoryEvent previousTransfer(@Param("item") long item,@Param("before") long before);
    @Insert("INSERT INTO cl_item_history(item_id,exchange_id,event_type,description,evidence_level,source_user_id,counterparty_user_id,occurred_at,recorded_at,corrects_event_id,ownership_started_at,ownership_ended_at) " +
        "VALUES(#{itemId},#{exchangeId},#{eventType},#{description},'SELF_REPORTED',#{sourceUserId},#{counterpartyUserId},#{occurredAt},#{recordedAt},#{correctsEventId},#{ownershipStartedAt},#{ownershipEndedAt})")
    @Options(useGeneratedKeys=true,keyProperty="id")
    int append(HistoryEvent row);
    @Insert("INSERT INTO cl_history_evidence(history_id,upload_id) VALUES(#{history},#{upload})")
    int evidence(@Param("history") long history,@Param("upload") String upload);
    @Select("SELECT upload_id FROM cl_history_evidence WHERE history_id=#{history} ORDER BY upload_id")
    List<String> evidenceIds(long history);
    @Select("SELECT COUNT(*) FROM cl_history_evidence v JOIN cl_item_history h ON h.id=v.history_id WHERE v.upload_id=#{upload} AND (#{admin}=TRUE OR h.source_user_id=#{actor} OR "+PAIR+")")
    int readableEvidence(@Param("upload") String upload,@Param("actor") long actor,@Param("admin") boolean admin);
}
