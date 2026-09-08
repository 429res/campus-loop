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
        Flyway.configure().dataSource(dataSource).cleanDisabled(true).load().migrate();
        List<Map<String,Object>> migrated=jdbc.queryForList("SELECT * FROM cl_exchange ORDER BY id");
        for(int i=0;i<migrated.size();i++) {
            assertNull(migrated.get(i).remove("request_digest"));
            assertNull(migrated.get(i).remove("rule_version"));
            assertNull(migrated.get(i).remove("creation_snapshot"));
            assertEquals(exchanges.get(i),migrated.get(i),"V8 must preserve all V2 exchange fields");
        }
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM cl_exchange_demand",Integer.class));
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
