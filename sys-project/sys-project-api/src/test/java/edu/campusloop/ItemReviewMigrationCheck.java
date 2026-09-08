package edu.campusloop;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in fresh-schema upgrade check: mysql-test.mjs --item-review-upgrade. Never cleans a schema. */
class ItemReviewMigrationCheck {
    @Test void upgradePreservesLegacyStatesVersionsHoldsAndContentWithoutInventingApprovals() {
        boolean mysql=Boolean.getBoolean("campus.mysql-test");
        String url=mysql?System.getenv("TEST_DB_URL"):"jdbc:h2:mem:campus_loop_review_upgrade_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        if(mysql && (url==null || !url.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/campus_loop_[a-z0-9_]*test(?:\\?.*)?")))
            throw new IllegalStateException("Explicit isolated localhost test database required");
        var dataSource=new DriverManagerDataSource(url,mysql?System.getenv("TEST_DB_USERNAME"):"sa",mysql?System.getenv("TEST_DB_PASSWORD"):"");
        JdbcTemplate jdbc=new JdbcTemplate(dataSource);
        Integer tables=jdbc.queryForObject(mysql?"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()":"SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='public'",Integer.class);
        assertEquals(0,tables,"Upgrade test must start in a NEW empty test schema; never drop existing data");
        Flyway.configure().dataSource(dataSource).target("6").cleanDisabled(true).load().migrate();
        jdbc.update("INSERT INTO cl_user(id,username,display_name,role,status) VALUES (91001,'upgrade_fixture','升级测试','USER','ACTIVE')");
        List<String> states=List.of("AVAILABLE","RESERVED","EXCHANGED","HIDDEN","DRAFT","PENDING_REVIEW");
        for(int i=0;i<states.size();i++) jdbc.update("INSERT INTO cl_item(id,owner_id,title,description,category_id,condition_level,tags_json,wanted_category_id,wanted_tags_json,status,version) VALUES (?,91001,'旧标题','旧内容',1,3,'[]',2,'[]',?,?)",92001+i,states.get(i),10+i);
        jdbc.update("INSERT INTO cl_exchange(id,initiator_id,status,version,idempotency_key,expires_at) VALUES (93001,91001,'READY',4,'upgrade_fixture',CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO cl_item_hold(item_id,exchange_id,expires_at) VALUES (92002,93001,CURRENT_TIMESTAMP)");
        List<Map<String,Object>> before=jdbc.queryForList("SELECT * FROM cl_item ORDER BY id");
        List<Map<String,Object>> holds=jdbc.queryForList("SELECT * FROM cl_item_hold");
        Flyway.configure().dataSource(dataSource).target("7").cleanDisabled(true).load().migrate();
        List<Map<String,Object>> exchanges=jdbc.queryForList("SELECT * FROM cl_exchange ORDER BY id");
        Flyway.configure().dataSource(dataSource).target("8").cleanDisabled(true).load().migrate();
        List<Map<String,Object>> migrated=jdbc.queryForList("SELECT * FROM cl_exchange ORDER BY id");
        for(int i=0;i<migrated.size();i++) {
            assertNull(migrated.get(i).remove("request_digest"));
            assertNull(migrated.get(i).remove("rule_version"));
            assertNull(migrated.get(i).remove("creation_snapshot"));
            assertEquals(exchanges.get(i),migrated.get(i),"V8 must preserve all V2 exchange fields");
        }
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM cl_exchange_demand",Integer.class));
        // Upgrade V8 records separately; V9 must preserve metadata and never invent legacy cancellation audits.
        jdbc.update("UPDATE cl_exchange SET request_digest=?,rule_version='independent-v2',creation_snapshot='{}' WHERE id=93001","a".repeat(64));
        jdbc.update("INSERT INTO cl_exchange(id,initiator_id,status,version,idempotency_key,expires_at) VALUES (93002,91001,'CANCELLED',2,'legacy_cancel',CURRENT_TIMESTAMP)");
        var beforeLifecycle=jdbc.queryForList("SELECT * FROM cl_exchange ORDER BY id");
        Flyway.configure().dataSource(dataSource).target("9").cleanDisabled(true).load().migrate();
        var afterLifecycle=jdbc.queryForList("SELECT * FROM cl_exchange ORDER BY id");
        for(int i=0;i<afterLifecycle.size();i++) {
            for(String column:List.of("cancelled_by","cancellation_reason","cancelled_at")) assertNull(afterLifecycle.get(i).remove(column));
            assertEquals(beforeLifecycle.get(i),afterLifecycle.get(i),"V9 must preserve every existing V8 field");
        }
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM cl_exchange_event",Integer.class));
        var beforeExpiry=jdbc.queryForList("SELECT * FROM cl_exchange ORDER BY id");
        Flyway.configure().dataSource(dataSource).target("10").cleanDisabled(true).load().migrate();
        var afterExpiry=jdbc.queryForList("SELECT * FROM cl_exchange ORDER BY id");
        for(int i=0;i<afterExpiry.size();i++) {
            assertNull(afterExpiry.get(i).remove("expiry_retry_at"));
            assertNull(afterExpiry.get(i).remove("expiry_failure_code"));
            assertEquals(0,((Number)afterExpiry.get(i).remove("expiry_retry_count")).intValue());
            assertEquals(beforeExpiry.get(i),afterExpiry.get(i),"V10 preserves deadlines, states and existing metadata");
        }

        jdbc.update("INSERT INTO cl_exchange_participant(exchange_id,user_id,offered_item_id,recipient_user_id,confirmed_at,handed_off_at) VALUES (93001,91001,92002,91001,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO cl_exchange_event(exchange_id,actor_id,event_type,previous_status,new_status,previous_version,new_version,occurred_at) VALUES (93001,91001,'CONFIRMED','AWAITING_CONFIRMATION','READY',3,4,CURRENT_TIMESTAMP)");
        var beforeParticipants=jdbc.queryForList("SELECT * FROM cl_exchange_participant ORDER BY id");
        var beforeHandoff=jdbc.queryForList("SELECT * FROM cl_exchange ORDER BY id");
        var beforeEvents=jdbc.queryForList("SELECT * FROM cl_exchange_event ORDER BY id");
        Flyway.configure().dataSource(dataSource).target("11").cleanDisabled(true).load().migrate();
        var afterHandoff=jdbc.queryForList("SELECT * FROM cl_exchange ORDER BY id");
        for(int i=0;i<afterHandoff.size();i++) {
            for(String column:List.of("disputed_by","dispute_reason","disputed_at")) assertNull(afterHandoff.get(i).remove(column));
            assertEquals(beforeHandoff.get(i),afterHandoff.get(i),"V11 preserves every previous exchange fact");
        }
        assertEquals(beforeEvents,jdbc.queryForList("SELECT * FROM cl_exchange_event ORDER BY id"));
        var afterParticipants=jdbc.queryForList("SELECT * FROM cl_exchange_participant ORDER BY id");
        for(int i=0;i<afterParticipants.size();i++) {
            assertNull(afterParticipants.get(i).remove("handed_off_note"));assertNull(afterParticipants.get(i).remove("received_note"));
            assertEquals(beforeParticipants.get(i),afterParticipants.get(i),"V11 does not manufacture or reinterpret existing handover facts");
        }
        jdbc.update("INSERT INTO cl_item_history(item_id,exchange_id,event_type,description,evidence_level,source_user_id,occurred_at,recorded_at) VALUES (92002,93001,'EXCHANGED','明确标记的旧迁移夹具','BOTH_CONFIRMED',91001,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO cl_upload(id,owner_id,url) VALUES ('11111111-1111-1111-1111-111111111111',91001,'/uploads/11111111-1111-1111-1111-111111111111.png')");
        var beforeHistory=jdbc.queryForList("SELECT * FROM cl_item_history ORDER BY id");
        var beforeUploads=jdbc.queryForList("SELECT * FROM cl_upload ORDER BY id");
        Flyway.configure().dataSource(dataSource).target("12").cleanDisabled(true).load().migrate();
        var afterHistory=jdbc.queryForList("SELECT * FROM cl_item_history ORDER BY id");
        for(int i=0;i<afterHistory.size();i++) {
            for(String field:List.of("corrects_event_id","ownership_started_at","ownership_ended_at")) assertNull(afterHistory.get(i).remove(field));
            assertEquals(beforeHistory.get(i),afterHistory.get(i),"V12 preserves source, authors, occurrence and record timestamps");
        }
        var afterUploads=jdbc.queryForList("SELECT * FROM cl_upload ORDER BY id");
        for(int i=0;i<afterUploads.size();i++) {assertEquals("PUBLIC",afterUploads.get(i).remove("visibility"));assertEquals(beforeUploads.get(i),afterUploads.get(i));}
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM cl_history_evidence",Integer.class));
        jdbc.update("INSERT INTO cl_item_history(id,item_id,event_type,description,evidence_level,source_user_id,occurred_at,ownership_ended_at) VALUES (94001,92001,'REPAIR','明确标记的V12自述升级夹具','SELF_REPORTED',91001,NULL,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO cl_history_evidence(history_id,upload_id) VALUES(94001,'11111111-1111-1111-1111-111111111111')");
        var beforeConfirmationHistory=jdbc.queryForList("SELECT * FROM cl_item_history ORDER BY id");
        var beforeConfirmationEvidence=jdbc.queryForList("SELECT * FROM cl_history_evidence ORDER BY history_id,upload_id");
        Flyway.configure().dataSource(dataSource).target("13").cleanDisabled(true).load().migrate();
        assertEquals(beforeConfirmationHistory,jdbc.queryForList("SELECT * FROM cl_item_history ORDER BY id"),"V13 must not upgrade or overwrite any legacy source or timestamp");
        assertEquals(beforeConfirmationEvidence,jdbc.queryForList("SELECT * FROM cl_history_evidence ORDER BY history_id,upload_id"));
        for(String table:List.of("cl_history_confirmation_request","cl_history_confirmation_member","cl_history_confirmation","cl_history_confirmation_withdrawal"))
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class),"V13 must not fabricate consent, a roster or confirmation");

        var beforeVerification=jdbc.queryForList("SELECT * FROM cl_item_history ORDER BY id");
        Flyway.configure().dataSource(dataSource).cleanDisabled(true).load().migrate();
        assertEquals(beforeVerification,jdbc.queryForList("SELECT * FROM cl_item_history ORDER BY id"),"V14 does not fabricate admin verification or rewrite old evidence levels");
        for(String table:List.of("cl_history_verification_state","cl_history_verification_audit")) assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class));
        List<Map<String,Object>> after=jdbc.queryForList("SELECT * FROM cl_item ORDER BY id");
        for(int i=0;i<after.size();i++) {
            assertEquals(i<3?"LEGACY_DIRECT":"UNREVIEWED",after.get(i).remove("review_basis"));
            assertEquals(before.get(i),after.get(i));
        }
        assertEquals(holds,jdbc.queryForList("SELECT * FROM cl_item_hold"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM cl_item_review_audit",Integer.class));
        jdbc.update("INSERT INTO cl_item(owner_id,title,description,category_id,condition_level,tags_json,wanted_category_id,wanted_tags_json) VALUES (91001,'默认状态','默认值验证',1,3,'[]',2,'[]')");
        assertEquals("PENDING_REVIEW",jdbc.queryForObject("SELECT status FROM cl_item WHERE title='默认状态'",String.class));
        new ResourceDatabasePopulator(new ClassPathResource("db/demo-data.sql")).execute(dataSource);
        assertEquals(6,jdbc.queryForObject("SELECT COUNT(*) FROM cl_item WHERE id BETWEEN 2001 AND 2006 AND status='AVAILABLE' AND review_basis='LEGACY_DIRECT'",Integer.class));
        List<Map<String,Object>> withDemo=jdbc.queryForList("SELECT * FROM cl_item ORDER BY id");
        new ResourceDatabasePopulator(new ClassPathResource("db/demo-data.sql")).execute(dataSource);
        assertEquals(withDemo,jdbc.queryForList("SELECT * FROM cl_item ORDER BY id"));
    }
}
