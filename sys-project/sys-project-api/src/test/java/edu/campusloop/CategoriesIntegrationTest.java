package edu.campusloop;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;
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
class CategoriesIntegrationTest {
    @DynamicPropertySource static void isolatedDatabase(DynamicPropertyRegistry registry) {
        boolean mysql = Boolean.getBoolean("campus.mysql-test");
        String url = mysql ? System.getenv("TEST_DB_URL") :
            "jdbc:h2:mem:campus_loop_categories_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        String username = mysql ? System.getenv("TEST_DB_USERNAME") : "sa";
        String password = mysql ? System.getenv("TEST_DB_PASSWORD") : "";
        if (mysql && (url == null || !url.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/campus_loop_[a-z0-9_]*test(?:\\?.*)?")))
            throw new IllegalStateException("Category tests require an explicitly isolated localhost campus_loop_*test schema");
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
    private final List<Long> userIds = new ArrayList<>(), categoryIds = new ArrayList<>();
    private String prefix;
    private Account admin, user;
    private record Account(long id, String token) {}

    @BeforeEach void fixtures() throws Exception {
        prefix = "cat_" + UUID.randomUUID().toString().substring(0, 8);
        admin = account(true); user = account(false);
    }
    @AfterEach void removeOnlyFixtures() {
        for (long id : userIds) {
            jdbc.update("DELETE FROM cl_demand_item WHERE demand_id IN (SELECT id FROM cl_demand WHERE owner_id=?)", id);
            jdbc.update("DELETE FROM cl_demand WHERE owner_id=?", id);
            jdbc.update("DELETE FROM cl_item_review_audit WHERE item_id IN (SELECT id FROM cl_item WHERE owner_id=?)",id);
            jdbc.update("DELETE FROM cl_item WHERE owner_id=?", id);
            jdbc.update("DELETE FROM cl_auth_session WHERE user_id=?", id);
            jdbc.update("DELETE FROM cl_user WHERE id=?", id);
        }
        for (long id : categoryIds) jdbc.update("DELETE FROM cl_category WHERE id=?", id);
    }

    @Test void adminCrudUsesDatabaseReadbackAndProtectsEveryRoute() throws Exception {
        JsonNode created = category("  " + prefix + " 图书  ", 3);
        long id = created.path("id").asLong();
        assertEquals(Set.of("id", "name", "status", "sortOrder", "version"), fields(created));
        assertEquals(prefix + " 图书", created.path("name").asText());
        assertEquals("ACTIVE", created.path("status").asText());
        assertEquals(0, created.path("version").asInt());
        assertEquals(created, call("GET", path(id), admin.token(), null, 200));
        assertEquals(created.path("name").asText(), jdbc.queryForObject("SELECT name FROM cl_category WHERE id=?", String.class, id));
        for (String method : List.of("GET", "POST", "PATCH", "DELETE")) {
            String url = method.equals("POST") ? path() : path(id) + (method.equals("DELETE") ? "?version=0" : "");
            Object body = method.equals("POST") ? Map.of("name", prefix) : method.equals("PATCH") ? Map.of("version", 0, "name", prefix) : null;
            call(method, url, user.token(), body, 403); call(method, url, null, body, 401);
        }
        call("GET", path(), user.token(), null, 403); call("GET", path(), null, null, 401);
        JsonNode updated = patch(id, 0, Map.of("name", prefix + " renamed", "sortOrder", 20, "status", "INACTIVE"), 200);
        assertEquals(1, updated.path("version").asInt());
        assertEquals(updated, call("GET", path(id), admin.token(), null, 200));
        assertEquals("INACTIVE", jdbc.queryForObject("SELECT status FROM cl_category WHERE id=?", String.class, id));
        assertEquals(20, jdbc.queryForObject("SELECT sort_order FROM cl_category WHERE id=?", Integer.class, id));
        patch(id, 0, Map.of("name", prefix + " lost"), 409);
        call("DELETE", path(id) + "?version=0", admin.token(), null, 409);
        JsonNode same = patch(id, 1, Map.of("status", "INACTIVE"), 200);
        assertEquals(2, same.path("version").asInt());
        assertEquals(updated.path("name"), same.path("name"));
        assertTrue(call("DELETE", path(id) + "?version=2", admin.token(), null, 200).isNull());
        call("GET", path(id), admin.token(), null, 404);
        call("DELETE", path(id) + "?version=2", admin.token(), null, 404);
    }

    @Test void strictInputsRejectUnknownFieldsNullsCoercionAndOutOfRangeValues() throws Exception {
        long id = category(prefix, 0).path("id").asLong();
        List<Object> invalidCreates = List.of(Map.of(), Map.of("name", " "), Map.of("name", "x".repeat(65)),
            Map.of("name", 1), Map.of("name", prefix, "ownerId", 1), Map.of("name", prefix, "version", 0),
            Map.of("name", prefix, "status", "INACTIVE"), Map.of("name", prefix, "parentId", 1),
            Map.of("name", prefix, "sortOrder", -1), Map.of("name", prefix, "sortOrder", 10000),
            Map.of("name", prefix, "sortOrder", 1.5), Map.of("name", prefix, "sortOrder", "1"),
            json.readTree("{\"name\":null}"), List.of());
        for (Object body : invalidCreates) call("POST", path(), admin.token(), body, 400);
        List<Object> invalidPatches = List.of(Map.of("version", 0), Map.of("name", prefix),
            Map.of("version", -1, "name", prefix), Map.of("version", "0", "name", prefix),
            Map.of("version", 0.5, "name", prefix), Map.of("version", 0, "status", "DELETED"),
            Map.of("version", 0, "name", " "), Map.of("version", 0, "name", "x".repeat(65)),
            Map.of("version", 0, "sortOrder", 10000), Map.of("version", 0, "sortOrder", "1"),
            Map.of("version", 0, "name_key", "forged"), Map.of("version", 0, "id", id),
            json.readTree("{\"version\":0,\"name\":null}"));
        for (Object body : invalidPatches) call("PATCH", path(id), admin.token(), body, 400);
        for (String query : List.of("", "?version=-1", "?version=1.5", "?version=invalid", "?version=2147483648"))
            call("DELETE", path(id) + query, admin.token(), null, 400);
        for (String query : List.of("?page=0", "?size=0", "?size=101", "?status=DELETED", "?keyword=" + "a".repeat(101)))
            call("GET", path() + query, admin.token(), null, 400);
        call("GET", path(0), admin.token(), null, 400);
        call("GET", "/api/categories?includeInactive=invalid", null, null, 400);
        assertEquals(0, call("GET", path(id), admin.token(), null, 200).path("version").asInt());
        jdbc.update("UPDATE cl_category SET version=? WHERE id=?", Integer.MAX_VALUE, id);
        patch(id, Integer.MAX_VALUE, Map.of("name", prefix + " limit"), 409);
    }

    @Test void publicCompatibilityAndAdminPaginationHaveStableOrderingAndCorrectCounts() throws Exception {
        long a = category(prefix + " A", 3).path("id").asLong();
        long b = category(prefix + " B", 1).path("id").asLong();
        long c = category(prefix + " C", 1).path("id").asLong();
        patch(b, 0, Map.of("status", "INACTIVE"), 200);
        assertFalse(ids(call("GET", "/api/categories", null, null, 200)).contains(b));
        List<Long> all = ids(call("GET", "/api/categories?includeInactive=true", null, null, 200));
        assertTrue(all.indexOf(b) < all.indexOf(c)); assertTrue(all.indexOf(c) < all.indexOf(a));
        JsonNode first = page("?keyword=" + prefix.toUpperCase(Locale.ROOT) + "&size=2");
        assertEquals(3, first.path("total").asInt()); assertEquals(List.of(b, c), ids(first.path("records")));
        JsonNode second = page("?keyword=" + prefix + "&size=2&page=2");
        assertEquals(3, second.path("total").asInt()); assertEquals(List.of(a), ids(second.path("records")));
        JsonNode beyond = page("?keyword=" + prefix + "&page=100");
        assertEquals(3, beyond.path("total").asInt()); assertTrue(beyond.path("records").isEmpty());
        assertEquals(2, page("?keyword=" + prefix + "&status=ACTIVE").path("total").asInt());
        assertEquals(List.of(b), ids(page("?keyword=" + prefix + "&status=INACTIVE").path("records")));
        JsonNode empty = page("?keyword=" + prefix + "missing");
        assertEquals(0, empty.path("total").asInt()); assertTrue(empty.path("records").isEmpty());
        assertEquals(1, empty.path("page").asInt()); assertEquals(12, empty.path("size").asInt());
        patch(b, 1, Map.of("status", "ACTIVE"), 200);
        assertTrue(ids(call("GET", "/api/categories", null, null, 200)).contains(b));
    }

    @Test void duplicateNamesIncludingInactiveAreConflictsWithoutPartialEdits() throws Exception {
        long a = category(prefix + " Same", 0).path("id").asLong();
        long b = category(prefix + " Other", 0).path("id").asLong();
        patch(a, 0, Map.of("status", "INACTIVE"), 200);
        call("POST", path(), admin.token(), Map.of("name", "  " + prefix.toUpperCase(Locale.ROOT) + " SAME  "), 409);
        patch(b, 0, Map.of("name", prefix + " same", "status", "INACTIVE", "sortOrder", 22), 409);
        JsonNode unchanged = call("GET", path(b), admin.token(), null, 200);
        assertEquals(0, unchanged.path("version").asInt()); assertEquals(0, unchanged.path("sortOrder").asInt());
        assertEquals("ACTIVE", unchanged.path("status").asText());
    }

    @Test void everyItemAndDemandReferenceIncludingHistoricalRowsProtectsDeletion() throws Exception {
        long own = category(prefix + " own", 0).path("id").asLong();
        long wanted = category(prefix + " wanted", 0).path("id").asLong();
        long needed = category(prefix + " demand", 0).path("id").asLong();
        long item = item(own, wanted);
        long demand = demand(needed, List.of());
        call("POST", "/api/items/" + item + "/withdraw", user.token(), Map.of("version", 0), 200);
        call("DELETE", "/api/demands/" + demand + "?version=0", user.token(), null, 200);
        for (long id : List.of(own, wanted, needed)) {
            call("DELETE", path(id) + "?version=0", admin.token(), null, 409);
            patch(id, 0, Map.of("status", "INACTIVE"), 200);
            call("DELETE", path(id) + "?version=1", admin.token(), null, 409);
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("DELETE FROM cl_category WHERE id=?", id));
        }
        JsonNode history = call("GET", "/api/items/mine/" + item, user.token(), null, 200);
        assertEquals(prefix + " own", history.path("categoryName").asText());
        assertEquals(prefix + " wanted", history.path("wantedCategoryName").asText());
        assertEquals("DELETED", jdbc.queryForObject("SELECT status FROM cl_demand WHERE id=?", String.class, demand));
    }

    @Test void disablingBlocksNewSelectionsAndActivationButPreservesHistoryAndWithdrawal() throws Exception {
        long category = category(prefix, 0).path("id").asLong();
        long item = item(category, category), demand = demand(category, List.of(item));
        patch(category, 0, Map.of("status", "INACTIVE", "name", prefix + " archived"), 200);
        call("POST", "/api/items", user.token(), itemBody(category, 1), 400);
        call("POST", "/api/items", user.token(), itemBody(1, category), 400);
        Map<String, Object> edit = new HashMap<>(itemBody(category, category)); edit.put("version", 0);
        call("PUT", "/api/items/" + item, user.token(), edit, 400);
        call("POST", "/api/demands", user.token(), demandBody(category, List.of()), 400);
        call("PATCH", "/api/demands/" + demand, user.token(), Map.of("version", 0, "description", "rejected", "offeredItemIds", List.of()), 400);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM cl_demand_item WHERE demand_id=?", Integer.class, demand));
        JsonNode itemView = call("GET", "/api/items/" + item, null, null, 200);
        assertEquals(prefix + " archived", itemView.path("categoryName").asText());
        assertEquals(0, itemView.path("version").asInt());
        JsonNode demandView = call("GET", "/api/demands/" + demand, user.token(), null, 200);
        assertEquals(prefix + " archived", demandView.path("categoryName").asText());
        assertEquals(0, demandView.path("version").asInt());
        call("PATCH", "/api/demands/" + demand + "/status", user.token(), Map.of("version", 0, "status", "ACTIVE"), 400);
        call("PATCH", "/api/demands/" + demand + "/status", user.token(), Map.of("version", 0, "status", "INACTIVE"), 200);
        call("PATCH", "/api/demands/" + demand + "/status", user.token(), Map.of("version", 1, "status", "ACTIVE"), 400);
        call("PATCH", "/api/demands/" + demand, user.token(), Map.of("version", 1, "categoryId", 1), 200);
        call("PATCH", "/api/demands/" + demand + "/status", user.token(), Map.of("version", 2, "status", "ACTIVE"), 200);
        call("POST", "/api/items/" + item + "/withdraw", user.token(), Map.of("version", 0), 200);
        patch(category, 1, Map.of("status", "ACTIVE"), 200);
        item(category, category);
        call("POST", "/api/items", user.token(), itemBody(999999999, 1), 400);
        call("POST", "/api/demands", user.token(), demandBody(999999999, List.of()), 400);
    }

    @Test void concurrentCreatesEnforceOneNormalizedNameAtTheDatabaseBoundary() throws Exception {
        List<Callable<MvcResult>> writes = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            String name = i % 2 == 0 ? prefix + " Shared" : prefix.toUpperCase(Locale.ROOT) + " SHARED";
            writes.add(() -> raw("POST", path(), admin.token(), Map.of("name", name)));
        }
        int success = 0;
        for (MvcResult result : race(writes)) {
            int status = result.getResponse().getStatus(); assertTrue(status == 200 || status == 409);
            if (status == 200) { success++; categoryIds.add(json.readTree(result.getResponse().getContentAsString()).at("/data/id").asLong()); }
        }
        assertEquals(1, success);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM cl_category WHERE name_key=?", Integer.class, prefix + " shared"));
    }

    @Test void concurrentPatchesAndDeletionNeverOverwriteAVersion() throws Exception {
        long id = category(prefix, 0).path("id").asLong();
        List<Integer> statuses = race(List.of(
            () -> raw("PATCH", path(id), admin.token(), Map.of("version", 0, "sortOrder", 1)),
            () -> raw("PATCH", path(id), admin.token(), Map.of("version", 0, "sortOrder", 2))))
            .stream().map(result -> result.getResponse().getStatus()).sorted().toList();
        assertEquals(List.of(200, 409), statuses);
        assertEquals(1, call("GET", path(id), admin.token(), null, 200).path("version").asInt());
        List<Integer> deletion = race(List.of(
            () -> raw("PATCH", path(id), admin.token(), Map.of("version", 1, "sortOrder", 3)),
            () -> raw("DELETE", path(id) + "?version=1", admin.token(), null)))
            .stream().map(result -> result.getResponse().getStatus()).sorted().toList();
        assertTrue(deletion.equals(List.of(200, 404)) || deletion.equals(List.of(200, 409)));
    }

    @Test void concurrentNewItemOrDemandVersusDeletionHasNoDanglingReference() throws Exception {
        for (int kind = 0; kind < 3; kind++) {
            long id = category(prefix + " race " + kind, 0).path("id").asLong();
            boolean demand = kind == 2;
            Object body = demand ? demandBody(id, List.of()) : kind == 0 ? itemBody(id, 1) : itemBody(1, id);
            List<MvcResult> results = race(List.of(
                () -> raw("POST", demand ? "/api/demands" : "/api/items", user.token(), body),
                () -> raw("DELETE", path(id) + "?version=0", admin.token(), null)));
            int write = results.get(0).getResponse().getStatus(), delete = results.get(1).getResponse().getStatus();
            assertTrue((write == 200 && delete == 409) || (write == 400 && delete == 200), "write=" + write + ", delete=" + delete);
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM cl_item i LEFT JOIN cl_category c ON c.id=i.category_id LEFT JOIN cl_category w ON w.id=i.wanted_category_id WHERE c.id IS NULL OR w.id IS NULL", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM cl_demand d LEFT JOIN cl_category c ON c.id=d.category_id WHERE c.id IS NULL", Integer.class));
        }
    }

    @Test void writersWaitingOnCategoryRecheckDisabledStateAfterCommit() throws Exception {
        long id = category(prefix, 0).path("id").asLong();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<MvcResult>> pending = new TransactionTemplate(transactions).execute(tx -> {
                jdbc.queryForObject("SELECT id FROM cl_category WHERE id=? FOR UPDATE", Long.class, id);
                Future<MvcResult> item = pool.submit(() -> raw("POST", "/api/items", user.token(), itemBody(id, 1)));
                Future<MvcResult> demand = pool.submit(() -> raw("POST", "/api/demands", user.token(), demandBody(id, List.of())));
                assertThrows(TimeoutException.class, () -> item.get(200, TimeUnit.MILLISECONDS));
                assertThrows(TimeoutException.class, () -> demand.get(200, TimeUnit.MILLISECONDS));
                jdbc.update("UPDATE cl_category SET status='INACTIVE',version=version+1 WHERE id=?", id);
                return List.of(item, demand);
            });
            for (Future<MvcResult> result : Objects.requireNonNull(pending)) assertEquals(400, result.get(15, TimeUnit.SECONDS).getResponse().getStatus());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM cl_item WHERE owner_id=?", Integer.class, user.id()));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM cl_demand WHERE owner_id=?", Integer.class, user.id()));
        } finally { pool.shutdownNow(); }
    }

    @Test void demandWaitingOnAnItemDoesNotTakeCategoryLockBeforeTheItemEditor() throws Exception {
        long id = category(prefix, 0).path("id").asLong(), item = item(id, 1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<MvcResult> pending = new TransactionTemplate(transactions).execute(tx -> {
                jdbc.queryForObject("SELECT id FROM cl_item WHERE id=? FOR UPDATE", Long.class, item);
                Future<MvcResult> demand = pool.submit(() -> raw("POST", "/api/demands", user.token(), demandBody(id, List.of(item))));
                assertThrows(TimeoutException.class, () -> demand.get(250, TimeUnit.MILLISECONDS));
                // Models an editor that already holds the item row, then validates the category.
                jdbc.queryForObject("SELECT id FROM cl_category WHERE id=? FOR UPDATE", Long.class, id);
                return demand;
            });
            assertEquals(200, Objects.requireNonNull(pending).get(15, TimeUnit.SECONDS).getResponse().getStatus());
        } finally { pool.shutdownNow(); }
    }

    @Test void migrationPreservesSeedFieldsAndEnforcesChecksAndNormalizedUniqueKey() throws Exception {
        JsonNode seed = call("GET", path(1), admin.token(), null, 200);
        assertEquals("图书教材", seed.path("name").asText()); assertEquals("ACTIVE", seed.path("status").asText());
        assertEquals(0, seed.path("sortOrder").asInt()); assertEquals(0, seed.path("version").asInt());
        long id = category(prefix, 0).path("id").asLong();
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO cl_category(name,name_key) VALUES (?,?)", prefix + " different", prefix));
        // MySQL HY000 (missing default / CHECK) and H2 use different Spring exception subtypes.
        assertThrows(DataAccessException.class, () -> jdbc.update("INSERT INTO cl_category(name) VALUES (?)", prefix + " missing key"));
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE cl_category SET status='DELETED' WHERE id=?", id));
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE cl_category SET sort_order=-1 WHERE id=?", id));
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE cl_category SET sort_order=10000 WHERE id=?", id));
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE cl_category SET version=-1 WHERE id=?", id));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM cl_category WHERE name=?", Integer.class, prefix + " missing key"));
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM cl_category WHERE id=?", String.class, id));
        assertEquals(0, jdbc.queryForObject("SELECT sort_order FROM cl_category WHERE id=?", Integer.class, id));
        assertEquals(0, jdbc.queryForObject("SELECT version FROM cl_category WHERE id=?", Integer.class, id));
    }

    private Account account(boolean administrator) throws Exception {
        String name = prefix + UUID.randomUUID().toString().substring(0, 8), password = UUID.randomUUID().toString();
        long id = call("POST", "/api/auth/register", null, Map.of("username", name, "password", password, "displayName", "隔离分类测试"), 200).path("id").asLong();
        userIds.add(id);
        if (administrator) jdbc.update("UPDATE cl_user SET role='ADMIN' WHERE id=?", id);
        return new Account(id, call("POST", "/api/auth/login", null, Map.of("username", name, "password", password), 200).path("token").asText());
    }
    private JsonNode category(String name, int order) throws Exception {
        JsonNode result = call("POST", path(), admin.token(), Map.of("name", name, "sortOrder", order), 200);
        categoryIds.add(result.path("id").asLong()); return result;
    }
    private JsonNode patch(long id, int version, Map<String, Object> changes, int status) throws Exception {
        Map<String, Object> body = new HashMap<>(changes); body.put("version", version);
        return call("PATCH", path(id), admin.token(), body, status);
    }
    private Map<String, Object> itemBody(long category, long wanted) {
        return Map.of("title", "隔离分类物品", "description", "虚构测试", "categoryId", category,
            "conditionLevel", 4, "tags", List.of(), "wantedCategoryId", wanted, "wantedTags", List.of());
    }
    private Map<String, Object> demandBody(long category, List<Long> items) {
        return Map.of("categoryId", category, "description", "虚构需求", "preferredTags", List.of(), "offeredItemIds", items);
    }
    private long item(long category, long wanted) throws Exception {
        long id=call("POST", "/api/items", user.token(), itemBody(category, wanted), 200).path("id").asLong();
        // Legacy fixture for category reference tests; publication review is tested separately.
        jdbc.update("UPDATE cl_item SET status='AVAILABLE',review_basis='LEGACY_DIRECT' WHERE id=?",id);return id;
    }
    private long demand(long category, List<Long> items) throws Exception { return call("POST", "/api/demands", user.token(), demandBody(category, items), 200).path("id").asLong(); }
    private String path() { return "/api/admin/categories"; }
    private String path(long id) { return path() + "/" + id; }
    private JsonNode page(String query) throws Exception { return call("GET", path() + query, admin.token(), null, 200); }
    private Set<String> fields(JsonNode node) { Set<String> fields = new HashSet<>(); node.fieldNames().forEachRemaining(fields::add); return fields; }
    private List<Long> ids(JsonNode array) { List<Long> ids = new ArrayList<>(); array.forEach(node -> ids.add(node.path("id").asLong())); return ids; }
    private List<MvcResult> race(List<Callable<MvcResult>> jobs) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(jobs.size());
        CountDownLatch ready = new CountDownLatch(jobs.size()), start = new CountDownLatch(1);
        try {
            List<Future<MvcResult>> pending = new ArrayList<>();
            for (Callable<MvcResult> job : jobs) pending.add(pool.submit(() -> {ready.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS)); return job.call();}));
            assertTrue(ready.await(5, TimeUnit.SECONDS)); start.countDown();
            List<MvcResult> results = new ArrayList<>();
            for (Future<MvcResult> result : pending) results.add(result.get(20, TimeUnit.SECONDS));
            return results;
        } finally { start.countDown(); pool.shutdownNow(); }
    }
    private MvcResult raw(String method, String path, String token, Object body) throws Exception {
        var request = request(HttpMethod.valueOf(method), path);
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType("application/json").content(json.writeValueAsString(body));
        return mvc.perform(request).andReturn();
    }
    private JsonNode call(String method, String path, String token, Object body, int expected) throws Exception {
        MvcResult result = raw(method, path, token, body);
        assertEquals(expected, result.getResponse().getStatus(), method + " " + path);
        JsonNode response = json.readTree(result.getResponse().getContentAsString());
        assertEquals(expected, response.path("code").asInt()); return response.path("data");
    }
}
