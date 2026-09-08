package edu.campusloop;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc(print=org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@ActiveProfiles(resolver=RegistrationClosedIntegrationTest.Profile.class)
class RegistrationClosedIntegrationTest {
    static class Profile implements ActiveProfilesResolver {
        @Override public String[] resolve(Class<?> testClass) {
            return new String[]{Boolean.getBoolean("campus.mysql-test")?"mysql-test":"test"};
        }
    }
    @DynamicPropertySource static void config(DynamicPropertyRegistry registry) {
        boolean mysql=Boolean.getBoolean("campus.mysql-test");
        String url=mysql?System.getenv("TEST_DB_URL"):"jdbc:h2:mem:campus_loop_registration_closed_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        String username=mysql?System.getenv("TEST_DB_USERNAME"):"sa";
        String password=mysql?System.getenv("TEST_DB_PASSWORD"):"";
        if(mysql && (url==null || !url.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/campus_loop_[a-z0-9_]*test(?:\\?.*)?")))
            throw new IllegalStateException("MySQL tests require an explicitly isolated localhost campus_loop_*test schema");
        if(username==null || password==null) throw new IllegalStateException("Explicit test database credentials are required");
        registry.add("spring.datasource.url",()->url);registry.add("spring.datasource.username",()->username);
        registry.add("spring.datasource.password",()->password);registry.add("spring.datasource.driver-class-name",()->mysql?"com.mysql.cj.jdbc.Driver":"org.h2.Driver");
        registry.add("spring.flyway.url",()->url);registry.add("spring.flyway.user",()->username);registry.add("spring.flyway.password",()->password);
        registry.add("campus.bootstrap-enabled",()->false);registry.add("campus.registration-mode",()->"CLOSED");
        registry.add("campus.jwt-secret",()->UUID.randomUUID().toString()+UUID.randomUUID());
    }
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate jdbc;

    @Test void defaultClosedModeRejectsRegistrationWithoutWritingUser() throws Exception {
        String username="closed_"+UUID.randomUUID().toString().substring(0,10);
        String body=json.writeValueAsString(Map.of("username",username,"password","Valid-"+UUID.randomUUID(),"displayName","关闭注册测试"));
        mvc.perform(post("/api/auth/register").contentType("application/json").content(body))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM cl_user WHERE username=?",Integer.class,username));
    }
}
