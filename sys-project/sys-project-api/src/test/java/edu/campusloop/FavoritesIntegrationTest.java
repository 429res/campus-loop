package edu.campusloop;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles(resolver = CampusIntegrationTest.Profile.class)
class FavoritesIntegrationTest {
    @DynamicPropertySource static void isolatedDatabase(DynamicPropertyRegistry registry) {
        boolean mysql = Boolean.getBoolean("campus.mysql-test");
        String url = mysql ? System.getenv("TEST_DB_URL") :
            "jdbc:h2:mem:campus_loop_favorites_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        String username = mysql ? System.getenv("TEST_DB_USERNAME") : "sa";
        String password = mysql ? System.getenv("TEST_DB_PASSWORD") : "";
        if (mysql && (url == null || !url.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/campus_loop_[a-z0-9_]*test(?:\\?.*)?")))
            throw new IllegalStateException("Favorites tests require an explicitly isolated localhost campus_loop_*test schema");
        if (username == null || password == null) throw new IllegalStateException("Explicit test credentials are required");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> username);
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.datasource.driver-class-name", () -> mysql ? "com.mysql.cj.jdbc.Driver" : "org.h2.Driver");
        registry.add("spring.flyway.url", () -> url);
        registry.add("spring.flyway.user", () -> username);
        registry.add("spring.flyway.password", () -> password);
        registry.add("campus.bootstrap-enabled", () -> false);
        registry.add("campus.registration-mode", () -> "DEVELOPMENT_SELF_SERVICE");
        registry.add("campus.jwt-secret", () -> UUID.randomUUID().toString() + UUID.randomUUID());
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> itemIds = new ArrayList<>();
    private Account owner, viewer;
    private record Account(long id, String username, String password, String token) {}

    @BeforeEach void createFixtures() throws Exception {
        owner = account();
        viewer = account();
    }

    @AfterEach void removeOnlyFixtures() {
        for (long itemId : itemIds) jdbc.update("DELETE FROM cl_favorite WHERE item_id=?", itemId);
        for (long userId : userIds) jdbc.update("DELETE FROM cl_favorite WHERE user_id=?", userId);
        for (long itemId : itemIds) jdbc.update("DELETE FROM cl_item_review_audit WHERE item_id=?", itemId);
        for (long itemId : itemIds) jdbc.update("DELETE FROM cl_item WHERE id=?", itemId);
        for (long userId : userIds) {
            jdbc.update("DELETE FROM cl_auth_session WHERE user_id=?", userId);
            jdbc.update("DELETE FROM cl_user WHERE id=?", userId);
        }
    }

    @Test void persistsAcrossRefreshLogoutAndLoginAndRepeatedAddKeepsItsOriginalPosition() throws Exception {
        long id = item(owner);
        JsonNode result = call("PUT", favorite(id), viewer.token(), null, 200);
        assertEquals(2, result.size());assertEquals(id, result.path("itemId").asLong());assertTrue(result.path("favorited").asBoolean());
        Map<String, Object> stored = favoriteRow(viewer.id(), id);
        JsonNode first = page(viewer.token(), 1, 12);
        assertEquals(1, first.path("total").asInt());
        JsonNode record = first.at("/records/0");
        assertTrue(record.path("itemVisible").asBoolean());
        assertEquals(call("GET", "/api/items/" + id, null, null, 200), record.path("item"));
        assertTrue(record.path("favoritedAt").asText().matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z"));
        call("PUT", favorite(id), viewer.token(), Map.of(), 200);
        assertEquals(stored, favoriteRow(viewer.id(), id));
        assertEquals(first, page(viewer.token(), 1, 12));
        call("POST", "/api/auth/logout", viewer.token(), null, 200);
        call("GET", "/api/favorites", viewer.token(), null, 401);
        String freshToken = login(viewer.username(), viewer.password());
        assertEquals(first, page(freshToken, 1, 12));
        jdbc.update("UPDATE cl_item SET title='当前公开标题' WHERE id=?", id);
        assertEquals("当前公开标题", page(freshToken, 1, 12).at("/records/0/item/title").asText());
        assertEquals(stored, favoriteRow(viewer.id(), id));
    }

    @Test void privateListsAndCancellationAlwaysUseTheSessionIncludingAdmins() throws Exception {
        long first = item(owner), second = item(owner);
        call("PUT", favorite(first), viewer.token(), null, 200);
        call("PUT", favorite(first), owner.token(), null, 200);
        call("PUT", favorite(second), owner.token(), null, 200);
        jdbc.update("UPDATE cl_user SET role='ADMIN' WHERE id=?", owner.id());
        assertEquals(1, page(viewer.token(), 1, 12).path("total").asInt());
        assertEquals(2, page(owner.token(), 1, 12).path("total").asInt());
        call("DELETE", favorite(first), owner.token(), null, 200);
        assertEquals(1, relationCount(viewer.id(), first));
        assertEquals(0, relationCount(owner.id(), first));
        assertEquals(second, page(owner.token(), 1, 12).at("/records/0/itemId").asLong());
        for (String token : List.of(owner.token(), viewer.token())) {
            call("GET", "/api/favorites?userId=" + viewer.id(), token, null, 400);
            call("GET", "/api/favorites?ownerId=" + owner.id(), token, null, 400);
            for (String method : List.of("PUT", "DELETE")) {
                call(method, favorite(second) + "?userId=" + viewer.id(), token, null, 400);
                call(method, favorite(second), token, Map.of("userId", viewer.id()), 400);
            }
        }
        assertEquals(1, relationCount(viewer.id(), first));
        assertEquals(1, relationCount(owner.id(), second));
    }

    @Test void invisibleTargetsBecomeSafePlaceholdersAndStillCountInFullPages() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            long id = item(owner); ids.add(id);
            call("PUT", favorite(id), viewer.token(), null, 200);
        }
        jdbc.update("UPDATE cl_favorite SET created_at='2026-01-01 00:00:00' WHERE user_id=?", viewer.id());
        // Both nonpublic states remain on the first page without exposing a content snapshot.
        jdbc.update("UPDATE cl_item SET status='HIDDEN',title='不可泄露标题',description='不可泄露说明',image_url='/uploads/private.png' WHERE id=?", ids.get(4));
        jdbc.update("UPDATE cl_item SET status='PENDING_REVIEW' WHERE id=?", ids.get(3));
        JsonNode first = page(viewer.token(), 1, 2);
        assertEquals(5, first.path("total").asInt());assertEquals(2, first.path("records").size());
        assertEquals(ids.get(4).longValue(), first.at("/records/0/itemId").asLong());
        assertEquals(ids.get(3).longValue(), first.at("/records/1/itemId").asLong());
        for (JsonNode record : first.path("records")) {
            Set<String> fields = new HashSet<>();record.fieldNames().forEachRemaining(fields::add);
            assertEquals(Set.of("itemId", "favoritedAt", "itemVisible", "item"), fields);
            assertFalse(record.path("itemVisible").asBoolean());assertTrue(record.path("item").isNull());
        }
        assertFalse(first.toString().contains("不可泄露"));assertFalse(first.toString().contains("private.png"));
        JsonNode second = page(viewer.token(), 2, 2);
        assertEquals(ids.get(2).longValue(), second.at("/records/0/itemId").asLong());
        assertEquals(2, second.path("records").size());
        assertEquals(1, page(viewer.token(), 3, 2).path("records").size());
        JsonNode beyond = page(viewer.token(), 4, 2);
        assertTrue(beyond.path("records").isEmpty());assertEquals(5, beyond.path("total").asInt());
        // Ownership does not grant private detail access through the favorites API.
        jdbc.update("UPDATE cl_item SET owner_id=? WHERE id=?", viewer.id(), ids.get(4));
        assertTrue(page(viewer.token(), 1, 2).at("/records/0/item").isNull());
        call("DELETE", favorite(ids.get(4)), viewer.token(), null, 200);
        assertEquals(4, page(viewer.token(), 1, 2).path("total").asInt());
    }

    @Test void addFollowsPublicVisibilityOnEveryCallAndDoesNotAlterItemOrMatchingState() throws Exception {
        long id = item(owner);
        Map<String, Object> itemBefore = jdbc.queryForMap("SELECT * FROM cl_item WHERE id=?", id);
        long holds = jdbc.queryForObject("SELECT COUNT(*) FROM cl_item_hold", Long.class);
        long exchanges = jdbc.queryForObject("SELECT COUNT(*) FROM cl_exchange", Long.class);
        for (String state : List.of("AVAILABLE", "RESERVED", "EXCHANGED")) {
            jdbc.update("UPDATE cl_item SET status=? WHERE id=?", state, id);
            call("PUT", favorite(id), viewer.token(), null, 200);
            JsonNode record = page(viewer.token(), 1, 12).at("/records/0");
            assertTrue(record.path("itemVisible").asBoolean());assertEquals(state, record.at("/item/status").asText());
            assertEquals(1, relationCount(viewer.id(), id));
        }
        Map<String, Object> original = favoriteRow(viewer.id(), id);
        for (String state : List.of("DRAFT", "PENDING_REVIEW", "HIDDEN")) {
            jdbc.update("UPDATE cl_item SET status=? WHERE id=?", state, id);
            call("PUT", favorite(id), viewer.token(), null, 404);
            call("PUT", favorite(id), owner.token(), null, 404);
            assertEquals(original, favoriteRow(viewer.id(), id));
            assertTrue(page(viewer.token(), 1, 12).at("/records/0/item").isNull());
        }
        call("PUT", favorite(999999999L), viewer.token(), null, 404);
        assertEquals(0, relationCount(viewer.id(), 999999999L));
        jdbc.update("UPDATE cl_item SET status='AVAILABLE' WHERE id=?", id);
        assertTrue(page(viewer.token(), 1, 12).at("/records/0/itemVisible").asBoolean());
        assertEquals(itemBefore, jdbc.queryForMap("SELECT * FROM cl_item WHERE id=?", id));
        assertEquals(holds, jdbc.queryForObject("SELECT COUNT(*) FROM cl_item_hold", Long.class));
        assertEquals(exchanges, jdbc.queryForObject("SELECT COUNT(*) FROM cl_exchange", Long.class));
    }

    @Test void actualOwnerWithdrawalHidesBookmarkedContentAndStillAllowsCancellation() throws Exception {
        long id = item(owner);
        call("PUT", favorite(id), viewer.token(), null, 200);
        Map<String, Object> saved = favoriteRow(viewer.id(), id);
        JsonNode hidden = call("POST", "/api/items/" + id + "/withdraw", owner.token(), Map.of("version", 0), 200);
        assertEquals("HIDDEN", hidden.path("status").asText());
        assertEquals(saved, favoriteRow(viewer.id(), id));
        call("GET", "/api/items/" + id, null, null, 404);
        call("PUT", favorite(id), viewer.token(), null, 404);
        JsonNode list = page(viewer.token(), 1, 12);
        assertEquals(1, list.path("total").asInt());
        assertFalse(list.at("/records/0/itemVisible").asBoolean());
        assertTrue(list.at("/records/0/item").isNull());
        call("DELETE", favorite(id), viewer.token(), null, 200);
        assertEquals(0, page(viewer.token(), 1, 12).path("total").asInt());
        assertEquals("HIDDEN", jdbc.queryForObject("SELECT status FROM cl_item WHERE id=?", String.class, id));
    }

    @Test void cancellationIsStableForAbsentHiddenAndRemovedRelationsAndReaddingCreatesANewPosition() throws Exception {
        long id = item(owner);
        JsonNode absent = call("DELETE", favorite(id), viewer.token(), null, 200);
        call("PUT", favorite(id), viewer.token(), null, 200);
        long oldId = ((Number)favoriteRow(viewer.id(), id).get("id")).longValue();
        jdbc.update("UPDATE cl_item SET status='HIDDEN' WHERE id=?", id);
        assertEquals(absent, call("DELETE", favorite(id), viewer.token(), Map.of(), 200));
        assertEquals(absent, call("DELETE", favorite(id), viewer.token(), null, 200));
        JsonNode missing = call("DELETE", favorite(999999999L), viewer.token(), null, 200);
        assertEquals(2, missing.size());assertEquals(999999999L, missing.path("itemId").asLong());assertFalse(missing.path("favorited").asBoolean());
        assertEquals(0, page(viewer.token(), 1, 12).path("total").asInt());
        assertEquals("HIDDEN", jdbc.queryForObject("SELECT status FROM cl_item WHERE id=?", String.class, id));
        jdbc.update("UPDATE cl_item SET status='AVAILABLE' WHERE id=?", id);
        call("PUT", favorite(id), viewer.token(), null, 200);
        assertTrue(((Number)favoriteRow(viewer.id(), id).get("id")).longValue() > oldId);
    }

    @Test void validatesPaginationIdPayloadAndSessionWithoutWritingRelations() throws Exception {
        long id = item(owner);
        for (String method : List.of("PUT", "DELETE")) {
            call(method, favorite(id), null, null, 401);
            for (String target : List.of("0", "-1", "1.5", "abc", "9223372036854775808"))
                call(method, "/api/items/" + target + "/favorite", viewer.token(), null, 400);
            for (Object body : List.of(Map.of("ownerId", owner.id()), Map.of("itemId", id), Map.of("role", "ADMIN"), List.of(), "bad"))
                call(method, favorite(id), viewer.token(), body, 400);
            call(method, favorite(id) + "?unknown=1", viewer.token(), null, 400);
        }
        call("GET", "/api/favorites", null, null, 401);
        for (String query : List.of("page=0", "page=-1", "size=0", "size=101", "page=2147483648", "page=1.5", "page=x", "page=1&page=2", "size=2&size=2", "keyword=x"))
            call("GET", "/api/favorites?" + query, viewer.token(), null, 400);
        JsonNode empty = page(viewer.token(), 1, 12);
        assertTrue(empty.path("records").isEmpty());assertEquals(0, empty.path("total").asInt());
        assertEquals(1, empty.path("page").asInt());assertEquals(12, empty.path("size").asInt());
        jdbc.update("UPDATE cl_auth_session SET expires_at='2000-01-01 00:00:00' WHERE user_id=?", viewer.id());
        call("GET", "/api/favorites", viewer.token(), null, 401);
        call("PUT", favorite(id), viewer.token(), null, 401);
        call("DELETE", favorite(id), viewer.token(), null, 401);
        assertEquals(0, relationCount(viewer.id(), id));
    }

    @Test void concurrentAddsAndRemovalsRemainUniqueAndIdempotent() throws Exception {
        long id = item(owner);
        race(Collections.nCopies(8, "PUT"), id);
        assertEquals(1, relationCount(viewer.id(), id));
        Map<String, Object> stored = favoriteRow(viewer.id(), id);
        race(Collections.nCopies(8, "PUT"), id);
        assertEquals(stored, favoriteRow(viewer.id(), id));
        race(Collections.nCopies(8, "DELETE"), id);
        assertEquals(0, relationCount(viewer.id(), id));
        race(List.of("PUT", "DELETE", "PUT", "DELETE"), id);
        assertTrue(relationCount(viewer.id(), id) <= 1);
        call("DELETE", favorite(id), viewer.token(), null, 200);
        assertEquals(0, relationCount(viewer.id(), id));
    }

    @Test void waitingAddRechecksHiddenStateAndWaitingRemoveRunsAfterAddCommits() throws Exception {
        long id = item(owner);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<MvcResult> add = new TransactionTemplate(transactions).execute(tx -> {
                jdbc.queryForObject("SELECT id FROM cl_item WHERE id=? FOR UPDATE", Long.class, id);
                Future<MvcResult> pending = pool.submit(() -> raw("PUT", favorite(id), viewer.token(), null));
                assertThrows(TimeoutException.class, () -> pending.get(250, TimeUnit.MILLISECONDS));
                jdbc.update("UPDATE cl_item SET status='HIDDEN' WHERE id=?", id);
                return pending;
            });
            assertEquals(404, Objects.requireNonNull(add).get(15, TimeUnit.SECONDS).getResponse().getStatus());
            assertEquals(0, relationCount(viewer.id(), id));
            Future<MvcResult> remove = new TransactionTemplate(transactions).execute(tx -> {
                jdbc.queryForObject("SELECT id FROM cl_item WHERE id=? FOR UPDATE", Long.class, id);
                Future<MvcResult> pending = pool.submit(() -> raw("DELETE", favorite(id), viewer.token(), null));
                assertThrows(TimeoutException.class, () -> pending.get(250, TimeUnit.MILLISECONDS));
                jdbc.update("INSERT INTO cl_favorite(user_id,item_id) VALUES (?,?)", viewer.id(), id);
                return pending;
            });
            assertEquals(200, Objects.requireNonNull(remove).get(15, TimeUnit.SECONDS).getResponse().getStatus());
            assertEquals(0, relationCount(viewer.id(), id));
        } finally {pool.shutdownNow();}
    }

    @Test void migrationEnforcesUniqueUserItemAndNonCascadingForeignKeys() throws Exception {
        long id = item(owner);
        call("PUT", favorite(id), viewer.token(), null, 200);
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO cl_favorite(user_id,item_id) VALUES (?,?)", viewer.id(), id));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO cl_favorite(user_id,item_id) VALUES (?,?)", 999999999L, id));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO cl_favorite(user_id,item_id) VALUES (?,?)", viewer.id(), 999999999L));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("DELETE FROM cl_item WHERE id=?", id));
        // Remove the auth FK first so this deletion specifically exercises the favorite FK.
        jdbc.update("DELETE FROM cl_auth_session WHERE user_id=?", viewer.id());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("DELETE FROM cl_user WHERE id=?", viewer.id()));
        assertEquals(1, relationCount(viewer.id(), id));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM cl_item WHERE id=?", Integer.class, id));
    }

    private void race(List<String> methods, long id) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(methods.size());
        CountDownLatch ready = new CountDownLatch(methods.size()), start = new CountDownLatch(1);
        try {
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (String method : methods) futures.add(pool.submit(() -> {
                ready.countDown();assertTrue(start.await(5, TimeUnit.SECONDS));
                return raw(method, favorite(id), viewer.token(), null);
            }));
            assertTrue(ready.await(5, TimeUnit.SECONDS));start.countDown();
            for (int i = 0; i < futures.size(); i++) {
                MvcResult result = futures.get(i).get(15, TimeUnit.SECONDS);
                assertEquals(200, result.getResponse().getStatus());
                JsonNode response = json.readTree(result.getResponse().getContentAsString());
                assertEquals(200, response.path("code").asInt());
                assertEquals(methods.get(i).equals("PUT"), response.at("/data/favorited").asBoolean());
            }
        } finally {start.countDown();pool.shutdownNow();}
    }

    private Account account() throws Exception {
        String name = "fav_" + UUID.randomUUID().toString().substring(0, 12), password = UUID.randomUUID().toString();
        long id = call("POST", "/api/auth/register", null, Map.of("username", name, "password", password, "displayName", "隔离收藏测试"), 200).path("id").asLong();
        userIds.add(id);
        return new Account(id, name, password, login(name, password));
    }
    private String login(String name, String password) throws Exception {
        return call("POST", "/api/auth/login", null, Map.of("username", name, "password", password), 200).path("token").asText();
    }
    private long item(Account account) throws Exception {
        long id = call("POST", "/api/items", account.token(), Map.of("title", "虚构收藏物品", "description", "隔离测试", "categoryId", 1,
            "conditionLevel", 4, "tags", List.of(), "wantedCategoryId", 2, "wantedTags", List.of()), 200).path("id").asLong();
        itemIds.add(id);
        // Legacy fixture for the existing favorite visibility/version contract.
        jdbc.update("UPDATE cl_item SET status='AVAILABLE',review_basis='LEGACY_DIRECT' WHERE id=?",id);return id;
    }
    private String favorite(long id) {return "/api/items/" + id + "/favorite";}
    private JsonNode page(String token, int page, int size) throws Exception {return call("GET", "/api/favorites?page=" + page + "&size=" + size, token, null, 200);}
    private int relationCount(long userId, long itemId) {return jdbc.queryForObject("SELECT COUNT(*) FROM cl_favorite WHERE user_id=? AND item_id=?", Integer.class, userId, itemId);}
    private Map<String, Object> favoriteRow(long userId, long itemId) {return jdbc.queryForMap("SELECT * FROM cl_favorite WHERE user_id=? AND item_id=?", userId, itemId);}
    private MvcResult raw(String method, String path, String token, Object body) throws Exception {
        var request = request(HttpMethod.valueOf(method), path);
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType("application/json").content(json.writeValueAsString(body));
        return mvc.perform(request).andReturn();
    }
    private JsonNode call(String method, String path, String token, Object body, int expected) throws Exception {
        MvcResult result = raw(method, path, token, body);
        // Status-only assertions never dump headers, tokens or registration passwords on failure.
        assertEquals(expected, result.getResponse().getStatus(), method + " " + path);
        JsonNode response = json.readTree(result.getResponse().getContentAsString());
        assertEquals(expected, response.path("code").asInt());return response.path("data");
    }
}
