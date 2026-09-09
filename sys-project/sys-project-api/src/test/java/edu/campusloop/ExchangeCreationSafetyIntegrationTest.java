package edu.campusloop;

import edu.campusloop.common.ApiException;
import edu.campusloop.exchange.ExchangeCreationCommand;
import edu.campusloop.exchange.ExchangeLifecycleRules.HandoffKind;
import edu.campusloop.web.exchange.service.ExchangeCreationTransaction;
import edu.campusloop.web.exchange.service.ExchangeLifecycleService;
import edu.campusloop.web.matching.service.IndependentMatchingService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Runs the real creation/lifecycle transactions against isolated H2 or the project MySQL test container. */
@SpringBootTest
@ActiveProfiles(resolver=CampusIntegrationTest.Profile.class)
class ExchangeCreationSafetyIntegrationTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        boolean mysql = Boolean.getBoolean("campus.mysql-test");
        String url = mysql ? System.getenv("TEST_DB_URL")
            : "jdbc:h2:mem:campus_loop_exchange_creation_safety_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        if (mysql && (url == null || !url.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/campus_loop_[a-z0-9_]*test(?:\\?.*)?")))
            throw new IllegalStateException("Creation safety tests require an isolated localhost campus_loop_*test schema");
        String username = mysql ? System.getenv("TEST_DB_USERNAME") : "sa";
        String password = mysql ? System.getenv("TEST_DB_PASSWORD") : "";
        if (username == null || password == null) throw new IllegalStateException("Explicit test credentials required");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> username);
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.datasource.driver-class-name", () -> mysql ? "com.mysql.cj.jdbc.Driver" : "org.h2.Driver");
        registry.add("spring.flyway.url", () -> url);
        registry.add("spring.flyway.user", () -> username);
        registry.add("spring.flyway.password", () -> password);
        registry.add("campus.bootstrap-enabled", () -> false);
        registry.add("campus.exchange-expiry.enabled", () -> false);
        registry.add("campus.jwt-secret", () -> UUID.randomUUID().toString() + UUID.randomUUID());
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired ExchangeCreationTransaction creation;
    @Autowired ExchangeLifecycleService lifecycle;
    @Autowired IndependentMatchingService matching;
    private final List<Long> users = new ArrayList<>();
    private long a, b, c, firstOffer, otherOffer, bOffer, cOffer, sharedDemand, bDemand, cDemand;
    private Map<String, List<Map<String, Object>>> baseline;

    @BeforeEach void fixtures() {
        baseline = businessRows();
        a = user(); b = user(); c = user();
        firstOffer = item(a, 1); otherOffer = item(a, 1);
        bOffer = item(b, 2); cOffer = item(c, 2);
        sharedDemand = demand(a, 2, firstOffer);
        jdbc.update("INSERT INTO cl_demand_item(demand_id,item_id) VALUES(?,?)", sharedDemand, otherOffer);
        bDemand = demand(b, 1, bOffer); cDemand = demand(c, 1, cOffer);
    }

    @AfterEach void cleanup() {
        for (long user : users) {
            for (long exchange : jdbc.queryForList("SELECT id FROM cl_exchange WHERE initiator_id=?", Long.class, user)) {
                for (String table : List.of("cl_item_history", "cl_item_hold", "cl_exchange_event", "cl_exchange_demand", "cl_exchange_participant"))
                    jdbc.update("DELETE FROM " + table + " WHERE exchange_id=?", exchange);
                jdbc.update("DELETE FROM cl_exchange WHERE id=?", exchange);
            }
        }
        for (long user : users) {
            jdbc.update("DELETE FROM cl_demand_item WHERE demand_id IN (SELECT id FROM cl_demand WHERE owner_id=?)", user);
            jdbc.update("DELETE FROM cl_demand WHERE owner_id=?", user);
        }
        for (long user : users) jdbc.update("DELETE FROM cl_item WHERE owner_id=?", user);
        for (long user : users) jdbc.update("DELETE FROM cl_user WHERE id=?", user);
        assertEquals(baseline, businessRows(), "Cleanup must preserve all pre-existing business rows");
    }

    @Test void oneDemandCannotEnterTwoLiveExchangesEvenWhenTheOfferedItemsDiffer() {
        var first = command(firstOffer, 0, bOffer, bDemand, sharedDemand, "shared-first");
        long id = creation.create(a, first);
        var before = businessRows();
        var other = command(otherOffer, 0, cOffer, cDemand, sharedDemand, "shared-second");
        assertEquals(409, assertThrows(ApiException.class, () -> creation.create(a, other)).getStatus());
        assertEquals(before, businessRows(), "Rejecting the second exchange must leave every reservation unchanged");
        assertEquals(id, creation.create(a, first), "Exact replay must bypass the selected demand's own live reference");
        assertEquals(before, businessRows());
    }

    @Test void cancelledExchangeReleasesItsDemandForANewPlan() {
        long first = creation.create(a, command(firstOffer, 0, bOffer, bDemand, sharedDemand, "cancel-first"));
        lifecycle.cancel(a, first, 0, "虚构测试取消");
        long second = creation.create(a, command(otherOffer, 0, cOffer, cDemand, sharedDemand, "cancel-second"));
        assertNotEquals(first, second);
        assertEquals("AWAITING_CONFIRMATION", jdbc.queryForObject("SELECT status FROM cl_exchange WHERE id=?", String.class, second));
    }

    @Test void anUnselectedDemandInAnotherExchangeDoesNotBlockAnIndependentSelectedDemand() {
        long alternative = demand(a, 2, otherOffer);
        jdbc.update("UPDATE cl_demand SET preferred_tags_json='[\"preferred\"]' WHERE id=?", alternative);
        jdbc.update("UPDATE cl_item SET tags_json='[\"preferred\"]' WHERE id=?", cOffer);
        creation.create(a, command(firstOffer, 0, bOffer, bDemand, sharedDemand, "unselected-first"));
        long second = creation.create(a, command(otherOffer, 0, cOffer, cDemand, alternative, "unselected-second"));
        assertEquals(List.of(alternative, cDemand).stream().sorted().toList(),
            jdbc.queryForList("SELECT demand_id FROM cl_exchange_demand WHERE exchange_id=? ORDER BY demand_id", Long.class, second));
    }

    @Test void recommendationsNeverReuseADemandAlreadySelectedByAnOngoingExchange() {
        creation.create(a, command(firstOffer, 0, bOffer, bDemand, sharedDemand, "recommend-busy"));
        var before = businessRows();
        var recommendations = matching.recommendations(a, "independent-v2").recommendations();
        assertTrue(recommendations.stream().flatMap(value -> value.flows().stream())
            .noneMatch(flow -> flow.demandId() == sharedDemand));
        assertTrue(recommendations.stream().noneMatch(value -> value.participants().stream()
            .anyMatch(person -> person.itemId() == otherOffer)));
        assertEquals(before, businessRows(), "Recommendation reads cannot reserve, release or rewrite a demand");
    }

    @Test void refreshedRecommendationsSelectAnAvailableAlternativeAndRestoreTheOriginalAfterCancellation() {
        long alternative = demand(a, 2, otherOffer); // Equal tag score: the smaller sharedDemand wins until occupied.
        long first = creation.create(a, command(firstOffer, 0, bOffer, bDemand, sharedDemand, "refresh-first"));
        var before = businessRows();
        var plan = matching.recommendations(a, "independent-v2").recommendations().stream()
            .filter(value -> value.participants().stream().map(person -> person.itemId()).collect(java.util.stream.Collectors.toSet())
                .equals(Set.of(otherOffer, cOffer))).findFirst().orElseThrow();
        assertEquals(alternative, plan.flows().stream().filter(flow -> flow.toUserId() == a).findFirst().orElseThrow().demandId());
        assertEquals(before, businessRows());
        var byItem = plan.participants().stream().collect(java.util.stream.Collectors.toMap(person -> person.itemId(), person -> person.itemVersion()));
        var refreshed = new ExchangeCreationCommand(plan.ruleVersion(), "refresh-second", plan.flows().stream()
            .map(flow -> new ExchangeCreationCommand.ExpectedFlow(flow.itemId(), byItem.get(flow.itemId()), flow.demandId(), flow.demandVersion())).toList());
        long second = creation.create(a, refreshed);
        assertNotEquals(first, second);
        lifecycle.cancel(a, first, 0, "虚构测试恢复需求");
        before = businessRows();
        assertTrue(matching.recommendations(a, "independent-v2").recommendations().stream()
            .flatMap(value -> value.flows().stream()).anyMatch(flow -> flow.demandId() == sharedDemand));
        assertEquals(before, businessRows());
    }

    @Test void exhaustedReleaseCapacityRejectsCreationWithoutPersistingAnyRows() {
        jdbc.update("UPDATE cl_item SET version=? WHERE id=?", Integer.MAX_VALUE - 1, firstOffer);
        var before = businessRows();
        var command = command(firstOffer, Integer.MAX_VALUE - 1, bOffer, bDemand, sharedDemand, "version-reject");
        assertEquals(409, assertThrows(ApiException.class, () -> creation.create(a, command)).getStatus());
        assertEquals(before, businessRows());
    }

    @Test void lastSafeVersionCanReserveCancelAndReplayWithoutStrandingHolds() {
        jdbc.update("UPDATE cl_item SET version=? WHERE id=?", Integer.MAX_VALUE - 2, firstOffer);
        var command = command(firstOffer, Integer.MAX_VALUE - 2, bOffer, bDemand, sharedDemand, "version-release");
        long id = creation.create(a, command);
        lifecycle.cancel(a, id, 0, "虚构边界测试取消");
        assertEquals(Integer.MAX_VALUE, jdbc.queryForObject("SELECT version FROM cl_item WHERE id=?", Integer.class, firstOffer));
        assertEquals("AVAILABLE", jdbc.queryForObject("SELECT status FROM cl_item WHERE id=?", String.class, firstOffer));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM cl_item_hold WHERE exchange_id=?", Integer.class, id));
        var before = businessRows();
        assertEquals(id, creation.create(a, command));
        assertEquals(before, businessRows());
    }

    @Test void exhaustedDemandVersionRejectsCreationBeforeItCanBlockHandoff() {
        jdbc.update("UPDATE cl_demand SET version=? WHERE id=?", Integer.MAX_VALUE, sharedDemand);
        var command = withSharedDemandVersion(command(firstOffer, 0, bOffer, bDemand, sharedDemand, "demand-reject"), Integer.MAX_VALUE);
        var before = businessRows();
        assertEquals(409, assertThrows(ApiException.class, () -> creation.create(a, command)).getStatus());
        assertEquals(before, businessRows());
    }

    @Test void lastSafeItemAndDemandVersionsCanCompleteTheFullExchange() {
        jdbc.update("UPDATE cl_item SET version=? WHERE id=?", Integer.MAX_VALUE - 2, firstOffer);
        jdbc.update("UPDATE cl_demand SET version=? WHERE id=?", Integer.MAX_VALUE - 1, sharedDemand);
        var command = withSharedDemandVersion(command(firstOffer, Integer.MAX_VALUE - 2, bOffer, bDemand, sharedDemand, "demand-complete"), Integer.MAX_VALUE - 1);
        long id = creation.create(a, command);
        lifecycle.confirm(a, id, 0); lifecycle.confirm(b, id, 1);
        lifecycle.handoff(a, id, 2, HandoffKind.HANDED_OFF, "已交付");
        lifecycle.handoff(b, id, 3, HandoffKind.RECEIVED, "已收到");
        lifecycle.handoff(b, id, 4, HandoffKind.HANDED_OFF, "已交付");
        lifecycle.handoff(a, id, 5, HandoffKind.RECEIVED, "已收到");
        assertEquals("COMPLETED", jdbc.queryForObject("SELECT status FROM cl_exchange WHERE id=?", String.class, id));
        assertEquals(Integer.MAX_VALUE, jdbc.queryForObject("SELECT version FROM cl_item WHERE id=?", Integer.class, firstOffer));
        assertEquals(b, jdbc.queryForObject("SELECT owner_id FROM cl_item WHERE id=?", Long.class, firstOffer));
        assertEquals(Integer.MAX_VALUE, jdbc.queryForObject("SELECT version FROM cl_demand WHERE id=?", Integer.class, sharedDemand));
        assertEquals("INACTIVE", jdbc.queryForObject("SELECT status FROM cl_demand WHERE id=?", String.class, sharedDemand));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM cl_item_hold WHERE exchange_id=?", Integer.class, id));
        var before = businessRows();
        assertEquals(id, creation.create(a, command));
        assertEquals(before, businessRows());
    }

    @Test void mysqlConcurrentPlansSharingOnlyADemandHaveExactlyOneWinner() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("campus.mysql-test"), "Concurrency evidence requires isolated MySQL");
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> concurrentCreate(start, command(firstOffer, 0, bOffer, bDemand, sharedDemand, "race-first")));
            var second = pool.submit(() -> concurrentCreate(start, command(otherOffer, 0, cOffer, cDemand, sharedDemand, "race-second")));
            start.countDown();
            assertEquals(List.of(200, 409), List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)).stream().sorted().toList());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM cl_exchange_demand WHERE demand_id=?", Integer.class, sharedDemand));
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM cl_item_hold h JOIN cl_exchange e ON e.id=h.exchange_id WHERE e.initiator_id=?", Integer.class, a));
        } finally {
            start.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private int concurrentCreate(CountDownLatch start, ExchangeCreationCommand command) throws InterruptedException {
        assertTrue(start.await(5, TimeUnit.SECONDS));
        try { creation.create(a, command); return 200; }
        catch (ApiException error) { return error.getStatus(); }
    }

    private ExchangeCreationCommand command(long offered, int version, long received, long recipientDemand, long ownDemand, String key) {
        return new ExchangeCreationCommand("independent-v2", key, List.of(
            new ExchangeCreationCommand.ExpectedFlow(offered, version, recipientDemand, 0),
            new ExchangeCreationCommand.ExpectedFlow(received, 0, ownDemand, 0)));
    }
    private ExchangeCreationCommand withSharedDemandVersion(ExchangeCreationCommand command, int version) {
        return new ExchangeCreationCommand(command.ruleVersion(), command.idempotencyKey(), command.flows().stream()
            .map(flow -> new ExchangeCreationCommand.ExpectedFlow(flow.itemId(), flow.itemVersion(), flow.demandId(),
                flow.demandId() == sharedDemand ? version : flow.demandVersion())).toList());
    }
    private long user() {
        String username = "creation_" + UUID.randomUUID();
        jdbc.update("INSERT INTO cl_user(username,display_name,role,status) VALUES(?,'虚构交换同学','USER','ACTIVE')", username);
        long id = jdbc.queryForObject("SELECT id FROM cl_user WHERE username=?", Long.class, username);
        users.add(id); return id;
    }
    private long item(long user, int category) {
        String title = "虚构物品_" + UUID.randomUUID();
        jdbc.update("INSERT INTO cl_item(owner_id,title,description,category_id,condition_level,tags_json,wanted_category_id,wanted_tags_json,status,review_basis) VALUES(?,?,'测试说明',?,1,'[]',6,'[]','AVAILABLE','LEGACY_DIRECT')", user, title, category);
        return jdbc.queryForObject("SELECT id FROM cl_item WHERE title=?", Long.class, title);
    }
    private long demand(long user, int category, long offered) {
        jdbc.update("INSERT INTO cl_demand(owner_id,category_id,description,preferred_tags_json,status,version) VALUES(?,?,'虚构需求','[]','ACTIVE',0)", user, category);
        long id = jdbc.queryForObject("SELECT MAX(id) FROM cl_demand WHERE owner_id=?", Long.class, user);
        jdbc.update("INSERT INTO cl_demand_item(demand_id,item_id) VALUES(?,?)", id, offered); return id;
    }
    private Map<String, List<Map<String, Object>>> businessRows() {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (String table : List.of("cl_user", "cl_item", "cl_demand", "cl_exchange", "cl_exchange_participant", "cl_exchange_event", "cl_item_history"))
            result.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id"));
        result.put("cl_exchange_demand", jdbc.queryForList("SELECT * FROM cl_exchange_demand ORDER BY exchange_id,demand_id"));
        result.put("cl_demand_item", jdbc.queryForList("SELECT * FROM cl_demand_item ORDER BY demand_id,item_id"));
        result.put("cl_item_hold", jdbc.queryForList("SELECT * FROM cl_item_hold ORDER BY item_id"));
        return result;
    }
}
