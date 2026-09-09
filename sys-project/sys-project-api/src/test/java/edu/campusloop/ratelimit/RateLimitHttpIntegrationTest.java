package edu.campusloop.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.campusloop.auth.PasswordService;
import edu.campusloop.web.user.entity.User;
import edu.campusloop.web.user.mapper.UserMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc(print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@Import(RateLimitHttpIntegrationTest.TimeConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RateLimitHttpIntegrationTest {
    private static final Path UPLOADS = temporaryUploads();
    private static Path temporaryUploads() {
        try { return Files.createTempDirectory("campus-loop-rate-limit-"); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        boolean mysql = Boolean.getBoolean("campus.mysql-test");
        String url = mysql ? System.getenv("TEST_DB_URL") : "jdbc:h2:mem:campus_loop_rate_limit_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        String username = mysql ? System.getenv("TEST_DB_USERNAME") : "sa";
        String password = mysql ? System.getenv("TEST_DB_PASSWORD") : "";
        if (mysql && (url == null || !url.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/campus_loop_[a-z0-9_]*test(?:\\?.*)?")))
            throw new IllegalStateException("MySQL tests require an explicitly isolated localhost campus_loop_*test schema");
        if (username == null || password == null) throw new IllegalStateException("Explicit test database credentials are required");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.driver-class-name", () -> mysql ? "com.mysql.cj.jdbc.Driver" : "org.h2.Driver");
        registry.add("spring.datasource.username", () -> username);
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.flyway.url", () -> url);
        registry.add("spring.flyway.user", () -> username);
        registry.add("spring.flyway.password", () -> password);
        registry.add("campus.jwt-secret", () -> "rate-limit-test-secret-32-bytes-minimum-value");
        registry.add("campus.upload-dir", () -> UPLOADS.toString());
        registry.add("campus.registration-mode", () -> "DEVELOPMENT_SELF_SERVICE");
        registry.add("campus.bootstrap-enabled", () -> false);
        registry.add("campus.demo-enabled", () -> false);
        registry.add("campus.exchange-expiry.enabled", () -> false);
        registry.add("campus.rate-limit.enabled", () -> true);
        registry.add("campus.rate-limit.login.limit", () -> 2);
        registry.add("campus.rate-limit.registration.limit", () -> 2);
        registry.add("campus.rate-limit.password.limit", () -> 2);
        registry.add("campus.rate-limit.upload.limit", () -> 2);
        registry.add("campus.rate-limit.login.window", () -> "1m");
        registry.add("campus.rate-limit.registration.window", () -> "1m");
        registry.add("campus.rate-limit.password.window", () -> "1m");
        registry.add("campus.rate-limit.upload.window", () -> "1m");
        registry.add("campus.rate-limit.trusted-proxies", () -> "");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserMapper users;
    @Autowired PasswordService passwords;
    @Autowired MutableClock clock;
    private String username, password, token;

    @BeforeAll void account() throws Exception {
        username = "rate_" + UUID.randomUUID().toString().substring(0, 12);
        password = "rate-limit riser password";
        User user = new User();
        user.setUsername(username); user.setPasswordHash(passwords.encode(password)); user.setDisplayName("限流测试同学");
        user.setRole("USER"); user.setStatus("ACTIVE"); user.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        users.insert(user);
        token = json.readTree(mvc.perform(remote(post("/api/auth/login"), "192.0.2.20")
            .contentType("application/json").content(json.writeValueAsString(Map.of("username", username, "password", password))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).at("/data/token").asText();
    }

    @AfterAll void cleanUploads() throws Exception {
        try (var paths = Files.walk(UPLOADS)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (Exception ignored) {} });
        }
    }

    @Test void loginUsesDirectPeerIgnoresSpoofedForwardedHeaderAndRecovers() throws Exception {
        String[] attemptedUsers = {username, "missing_" + UUID.randomUUID().toString().substring(0, 8)};
        for (int index = 0; index < attemptedUsers.length; index++) {
            String body = json.writeValueAsString(Map.of("username", attemptedUsers[index], "password", "incorrect password"));
            mvc.perform(remote(post("/api/auth/login"), "203.0.113.9").header("X-Forwarded-For", "198.51.100." + (index + 1))
                .contentType("application/json").content(body))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.msg").value("账号或密码不正确"));
        }
        String body = json.writeValueAsString(Map.of("username", username, "password", "incorrect password"));
        mvc.perform(remote(post("/api/auth/login"), "203.0.113.9").header("X-Forwarded-For", "198.51.100.3")
            .header("Origin", "http://localhost:5174")
            .contentType("application/json").content(body))
            .andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "60"))
            .andExpect(header().exists("X-RateLimit-Reset"))
            .andExpect(header().string("Access-Control-Expose-Headers", "Retry-After, X-RateLimit-Reset"))
            .andExpect(jsonPath("$.code").value(429))
            .andExpect(jsonPath("$.msg").value("请求过于频繁，请稍后重试"));
        clock.advance(Duration.ofMinutes(1));
        mvc.perform(remote(post("/api/auth/login"), "203.0.113.9").contentType("application/json").content(body))
            .andExpect(status().isUnauthorized());
    }

    @Test void authenticatedUploadSharesQuotaAcrossAddresses() throws Exception {
        byte[] png = png();
        for (String address : new String[]{"192.0.2.31", "192.0.2.32"}) {
            mvc.perform(remote(multipart("/api/uploads").file(new MockMultipartFile("file", "test.png", "image/png", png)), address)
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        }
        mvc.perform(remote(multipart("/api/uploads/evidence").file(new MockMultipartFile("file", "test.png", "image/png", png)), "192.0.2.33")
            .header("Authorization", "Bearer " + token)).andExpect(status().isTooManyRequests());
    }

    @Test void passwordAndRegistrationHaveIndependentLimitsWhileReadsRemainAvailable() throws Exception {
        String change = json.writeValueAsString(Map.of("currentPassword", "wrong password", "newPassword", "another valid password"));
        for (int i = 0; i < 2; i++) mvc.perform(remote(post("/api/auth/password"), "192.0.2." + (40 + i))
            .header("Authorization", "Bearer " + token).contentType("application/json").content(change)).andExpect(status().isBadRequest());
        mvc.perform(remote(post("/api/auth/password"), "192.0.2.42").header("Authorization", "Bearer " + token)
            .contentType("application/json").content(change)).andExpect(status().isTooManyRequests());

        String invalidRegistration = "{\"username\":\"x\",\"password\":\"short\",\"displayName\":\"x\"}";
        for (int i = 0; i < 2; i++) mvc.perform(remote(post("/api/auth/register"), "198.51.100.50")
            .contentType("application/json").content(invalidRegistration)).andExpect(status().isBadRequest());
        mvc.perform(remote(post("/api/auth/register"), "198.51.100.50").contentType("application/json").content(invalidRegistration))
            .andExpect(status().isTooManyRequests());

        for (int i = 0; i < 5; i++) mvc.perform(get("/api/health")).andExpect(status().isOk());
    }

    private static MockHttpServletRequestBuilder remote(MockHttpServletRequestBuilder builder, String address) {
        return builder.with(request -> { request.setRemoteAddr(address); return request; });
    }

    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfiguration {
        @Bean @Primary MutableClock mutableClock() { return new MutableClock(Instant.parse("2026-09-08T00:00:00Z")); }
    }

    static final class MutableClock extends Clock {
        private Instant instant;
        MutableClock(Instant instant) { this.instant = instant; }
        synchronized void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public synchronized Instant instant() { return instant; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
