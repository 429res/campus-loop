package edu.campusloop.web.history.mapper;
import edu.campusloop.web.history.entity.HistoryEvent;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;
public interface HistoryVerificationMapper {
    record Audit(long historyId,int version,long adminId,String idempotencyKey,String requestJson,String decision,String scope,String reason,String snapshotHash,String snapshotJson,LocalDateTime decidedAt) {}
    String STATE="CASE WHEN a.history_id IS NOT NULL THEN a.decision WHEN EXISTS(SELECT 1 FROM cl_item_history c WHERE c.corrects_event_id=h.id) THEN 'SUPERSEDED' ELSE 'PENDING' END";
    String FROM=" FROM cl_item_history h LEFT JOIN cl_history_verification_audit a ON a.history_id=h.id WHERE (h.event_type IN ('REPAIR','TRANSFER') AND h.evidence_level='SELF_REPORTED' OR h.event_type='EXCHANGED' AND h.evidence_level='BOTH_CONFIRMED') AND (#{status} IS NULL OR "+STATE+"=#{status})";
    @Select("SELECT COUNT(*)"+FROM) long count(String status);
    @Select("SELECT h.id"+FROM+" ORDER BY h.recorded_at DESC,h.id DESC LIMIT #{size} OFFSET #{offset}") List<Long> page(@Param("status") String status,@Param("size") int size,@Param("offset") long offset);
    /** Locks only the event, not the joined author row. */
    @Select("SELECT h.*,(SELECT c.id FROM cl_item_history c WHERE c.corrects_event_id=h.id) AS corrected_by_event_id FROM cl_item_history h WHERE h.id=#{id} FOR UPDATE") HistoryEvent current(long id);
    @Select("SELECT * FROM cl_history_verification_audit WHERE history_id=#{id}") Audit audit(long id);
    @Select("SELECT * FROM cl_history_verification_audit WHERE admin_id=#{admin} AND idempotency_key=#{key}") Audit replay(@Param("admin") long admin,@Param("key") String key);
    @Insert("INSERT INTO cl_history_verification_state(history_id) VALUES(#{id})") int prepare(long id);
    @Update("UPDATE cl_history_verification_state SET version=version+1,status=#{status} WHERE history_id=#{id} AND version=#{version} AND status='PENDING'") int decide(@Param("id") long id,@Param("version") int version,@Param("status") String status);
    @Insert("INSERT INTO cl_history_verification_audit(history_id,version,admin_id,idempotency_key,request_json,decision,scope,reason,snapshot_hash,snapshot_json,decided_at) VALUES(#{historyId},#{version},#{adminId},#{idempotencyKey},#{requestJson},#{decision},#{scope},#{reason},#{snapshotHash},#{snapshotJson},#{decidedAt})") int append(Audit audit);
}
