package edu.campusloop.web.exchange.service;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.time.*;
import java.time.temporal.ChronoUnit;

/** Shared creation/lifecycle clock; matches existing TIMESTAMP second precision and UTC sessions. */
@Component
public class ExchangeDatabaseClock {
    private final JdbcTemplate jdbc;
    public ExchangeDatabaseClock(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public LocalDateTime now() {
        return jdbc.execute((ConnectionCallback<LocalDateTime>) connection -> {
            boolean mysql=connection.getMetaData().getDatabaseProductName().equals("MySQL");
            try(var statement=connection.createStatement();var result=statement.executeQuery(mysql?"SELECT UTC_TIMESTAMP()":"SELECT CURRENT_TIMESTAMP")) {
                result.next();
                return mysql?result.getObject(1,LocalDateTime.class):result.getObject(1,OffsetDateTime.class)
                    .withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime().truncatedTo(ChronoUnit.SECONDS);
            }
        });
    }
}
