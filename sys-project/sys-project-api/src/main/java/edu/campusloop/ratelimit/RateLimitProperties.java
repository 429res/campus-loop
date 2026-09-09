package edu.campusloop.ratelimit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.AssertTrue;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Component
@Validated
@ConfigurationProperties(prefix = "campus.rate-limit")
public class RateLimitProperties {
    private boolean enabled = true;
    @Min(100) private int maxKeys = 10_000;
    private String trustedProxies = "";
    @Valid private Policy login = new Policy(10, Duration.ofMinutes(1));
    @Valid private Policy registration = new Policy(5, Duration.ofMinutes(10));
    @Valid private Policy password = new Policy(5, Duration.ofMinutes(10));
    @Valid private Policy upload = new Policy(20, Duration.ofMinutes(1));

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMaxKeys() { return maxKeys; }
    public void setMaxKeys(int maxKeys) { this.maxKeys = maxKeys; }
    public String getTrustedProxies() { return trustedProxies; }
    public void setTrustedProxies(String trustedProxies) { this.trustedProxies = trustedProxies == null ? "" : trustedProxies; }
    public Policy getLogin() { return login; }
    public void setLogin(Policy login) { this.login = login; }
    public Policy getRegistration() { return registration; }
    public void setRegistration(Policy registration) { this.registration = registration; }
    public Policy getPassword() { return password; }
    public void setPassword(Policy password) { this.password = password; }
    public Policy getUpload() { return upload; }
    public void setUpload(Policy upload) { this.upload = upload; }

    public static class Policy {
        @Min(1) private int limit;
        @NotNull private Duration window;

        public Policy() {}
        public Policy(int limit, Duration window) { this.limit = limit; this.window = window; }
        public int getLimit() { return limit; }
        public void setLimit(int limit) { this.limit = limit; }
        public Duration getWindow() { return window; }
        public void setWindow(Duration window) { this.window = window; }
        @AssertTrue(message = "限流窗口不得短于 1 秒")
        public boolean isWindowValid() { return window != null && window.compareTo(Duration.ofSeconds(1)) >= 0; }
    }
}
