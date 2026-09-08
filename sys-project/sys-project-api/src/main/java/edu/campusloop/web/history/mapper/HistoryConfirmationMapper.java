package edu.campusloop.web.history.mapper;

import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;

/** All source-chain writes append; no update/delete operation. */
public interface HistoryConfirmationMapper {
    record Request(long historyId,long exchangeId,String snapshotHash,String snapshotJson,LocalDateTime requestedAt) {}
    record Member(long userId,String displayName,LocalDateTime confirmedAt) {}
    record Withdrawal(long actorId,String reason,LocalDateTime withdrawnAt) {}
    @Select("SELECT history_id,exchange_id,snapshot_hash,snapshot_json,requested_at FROM cl_history_confirmation_request WHERE history_id=#{id}")
    Request request(long id);
    @Select("SELECT m.user_id,u.display_name,c.confirmed_at FROM cl_history_confirmation_member m JOIN cl_user u ON u.id=m.user_id LEFT JOIN cl_history_confirmation c ON c.history_id=m.history_id AND c.user_id=m.user_id WHERE m.history_id=#{id} ORDER BY m.user_id")
    List<Member> members(long id);
    @Select("SELECT actor_id,reason,withdrawn_at FROM cl_history_confirmation_withdrawal WHERE history_id=#{id}")
    Withdrawal withdrawal(long id);
    @Insert("INSERT INTO cl_history_confirmation_request(history_id,exchange_id,snapshot_hash,snapshot_json,requested_at) VALUES(#{historyId},#{exchangeId},#{snapshotHash},#{snapshotJson},#{requestedAt})")
    int appendRequest(Request request);
    @Insert("INSERT INTO cl_history_confirmation_member(history_id,exchange_id,user_id) VALUES(#{history},#{exchange},#{user})")
    int appendMember(@Param("history") long history,@Param("exchange") long exchange,@Param("user") long user);
    @Insert("INSERT INTO cl_history_confirmation(history_id,user_id,snapshot_hash,confirmed_at) VALUES(#{history},#{user},#{hash},#{at})")
    int appendConfirmation(@Param("history") long history,@Param("user") long user,@Param("hash") String hash,@Param("at") LocalDateTime at);
    @Insert("INSERT INTO cl_history_confirmation_withdrawal(history_id,snapshot_hash,actor_id,reason,withdrawn_at) VALUES(#{history},#{hash},#{actor},#{reason},#{at})")
    int appendWithdrawal(@Param("history") long history,@Param("hash") String hash,@Param("actor") long actor,@Param("reason") String reason,@Param("at") LocalDateTime at);
}
