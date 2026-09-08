package edu.campusloop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.campusloop.auth.PasswordService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles(resolver = CampusIntegrationTest.Profile.class)
@Transactional
class MatchingLimitsIntegrationTest {
    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        // Keep the same strict localhost test-schema and ambient-environment safeguards.
        CampusIntegrationTest.config(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PasswordService passwords;
    private String token;
    private long ownerId;
    private long initialHoldCount, initialExchangeCount;

    @BeforeEach
    void createIsolatedScenario() throws Exception {
        initialHoldCount = jdbc.queryForObject("SELECT COUNT(*) FROM cl_item_hold", Long.class);
        initialExchangeCount = jdbc.queryForObject("SELECT COUNT(*) FROM cl_exchange", Long.class);
        // This class is transactionally rolled back on the verified test database.
        jdbc.update("UPDATE cl_item SET status='HIDDEN'");
        String username = "limit_" + UUID.randomUUID().toString().substring(0, 12);
        String password = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO cl_user(username,password_hash,display_name,role,status) VALUES (?,?,?,'ADMIN','ACTIVE')",
            username, passwords.encode(password), "限额回归管理员");
        ownerId = jdbc.queryForObject("SELECT id FROM cl_user WHERE username=?", Long.class, username);
        var response = mvc.perform(post("/api/auth/login").contentType("application/json")
            .content(json.writeValueAsString(Map.of("username", username, "password", password))))
            .andReturn().getResponse();
        assertEquals(200, response.getStatus());
        token = json.readTree(response.getContentAsString()).at("/data/token").asText();
    }

    @Test
    void candidateOverflowDoesNotBreakBasicStatistics() throws Exception {
        for (int i = 0; i < 201; i++) item(ownerId);
        assertMatchingLimit("200");
        assertLimitedStats(201);
    }

    @Test
    void denseResultsReturn422BeforeExhaustingMemoryAndKeepStatistics() throws Exception {
        // 15 distinct users in one category yield 105 pairs + 910 directed triples.
        for (int i = 0; i < 15; i++) {
            String username = "dense_" + UUID.randomUUID().toString().substring(0, 12);
            jdbc.update("INSERT INTO cl_user(username,display_name,role,status) VALUES (?,?,'USER','ACTIVE')",
                username, "密集匹配测试同学");
            item(jdbc.queryForObject("SELECT id FROM cl_user WHERE username=?", Long.class, username));
        }
        assertMatchingLimit("1000");
        assertLimitedStats(15);
    }

    @Test
    void zeroRecommendationsRemainAnAvailableNumericCount() throws Exception {
        JsonNode stats = stats();
        assertEquals(0, stats.path("recommendations").asLong(-1));
        assertEquals("AVAILABLE", stats.path("recommendationsStatus").asText());
        assertBasicCounts(stats, 0);
    }

    private void item(long owner) {
        jdbc.update("INSERT INTO cl_item(owner_id,title,description,category_id,condition_level,tags_json,wanted_category_id,wanted_tags_json,status) " +
            "VALUES (?,'限额测试物品','独立回归数据',1,3,'[]',1,'[]','AVAILABLE')", owner);
    }

    private void assertMatchingLimit(String limit) throws Exception {
        var response = mvc.perform(get("/api/matches")).andReturn().getResponse();
        assertEquals(422, response.getStatus());
        JsonNode body = json.readTree(response.getContentAsString());
        assertEquals(422, body.path("code").asInt());
        assertTrue(body.path("msg").asText().contains(limit));
    }

    private void assertLimitedStats(long available) throws Exception {
        JsonNode stats = stats();
        assertTrue(stats.has("recommendations") && stats.path("recommendations").isNull());
        assertEquals("LIMIT_EXCEEDED", stats.path("recommendationsStatus").asText());
        assertBasicCounts(stats, available);
        assertEquals(initialHoldCount, jdbc.queryForObject("SELECT COUNT(*) FROM cl_item_hold", Long.class));
        assertEquals(initialExchangeCount, jdbc.queryForObject("SELECT COUNT(*) FROM cl_exchange", Long.class));
    }

    private void assertBasicCounts(JsonNode stats, long available) {
        assertEquals(jdbc.queryForObject("SELECT COUNT(*) FROM cl_user", Long.class), stats.path("users").asLong());
        assertEquals(jdbc.queryForObject("SELECT COUNT(*) FROM cl_item", Long.class), stats.path("items").asLong());
        assertEquals(available, stats.path("availableItems").asLong());
    }

    private JsonNode stats() throws Exception {
        var response = mvc.perform(get("/api/admin/stats").header("Authorization", "Bearer " + token))
            .andReturn().getResponse();
        assertEquals(200, response.getStatus());
        JsonNode body = json.readTree(response.getContentAsString());
        assertEquals(200, body.path("code").asInt());
        return body.path("data");
    }
}
