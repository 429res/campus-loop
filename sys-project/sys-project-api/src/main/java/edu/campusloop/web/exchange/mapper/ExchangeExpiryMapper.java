package edu.campusloop.web.exchange.mapper;

import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

/** Candidate reads are hints. The shared lifecycle transaction alone authorizes release. */
public interface ExchangeExpiryMapper {
    @Select("SELECT e.id FROM cl_exchange e WHERE e.status IN ('AWAITING_CONFIRMATION','READY') " +
        "AND e.expires_at<=#{now} AND (e.expiry_retry_at IS NULL OR e.expiry_retry_at<=#{now}) " +
        "AND e.rule_version='independent-v2' AND e.request_digest IS NOT NULL AND e.creation_snapshot IS NOT NULL " +
        "AND NOT EXISTS(SELECT 1 FROM cl_exchange_participant p WHERE p.exchange_id=e.id " +
        "AND (p.handed_off_at IS NOT NULL OR p.received_at IS NOT NULL)) " +
        "ORDER BY e.expires_at,e.id LIMIT #{limit}")
    List<Long> due(@Param("now") LocalDateTime now,@Param("limit") int limit);

    // Run only after the failed lifecycle transaction has rolled back. Concurrent terminal transitions win.
    @Update("UPDATE cl_exchange SET expiry_retry_at=TIMESTAMPADD(SECOND, " +
        "LEAST(3600,30*POWER(2,LEAST(expiry_retry_count,7))),CAST(#{now} AS DATETIME)), " +
        "expiry_retry_count=LEAST(expiry_retry_count,2147483646)+1,expiry_failure_code=#{code} " +
        "WHERE id=#{id} AND status IN ('AWAITING_CONFIRMATION','READY') AND expires_at<=#{now} " +
        "AND (expiry_retry_at IS NULL OR expiry_retry_at<=#{now}) " +
        "AND NOT EXISTS(SELECT 1 FROM cl_exchange_participant p WHERE p.exchange_id=cl_exchange.id " +
        "AND (p.handed_off_at IS NOT NULL OR p.received_at IS NOT NULL))")
    int defer(@Param("id") long id,@Param("now") LocalDateTime now,@Param("code") String code);
}
