package edu.campusloop.ratelimit;

import edu.campusloop.auth.AuthInterceptor;
import edu.campusloop.web.user.entity.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {
    private final RateLimitProperties properties;
    private final FixedWindowRateLimiter limiter;
    private final ClientAddressResolver addresses;

    public RateLimitInterceptor(RateLimitProperties properties, FixedWindowRateLimiter limiter, ClientAddressResolver addresses) {
        this.properties = properties;
        this.limiter = limiter;
        this.addresses = addresses;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!properties.isEnabled()) return true;
        String route = request.getMethod() + " " + request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        Selection selection = switch (route) {
            case "POST /api/auth/login" -> anonymous("login", properties.getLogin(), request);
            case "GET /api/auth/captcha", "POST /api/auth/email-code", "POST /api/account/email-code", "POST /api/auth/register" -> anonymous("registration", properties.getRegistration(), request);
            case "POST /api/auth/password" -> authenticated("password", properties.getPassword(), request);
            case "POST /api/uploads", "POST /api/uploads/evidence" -> authenticated("upload", properties.getUpload(), request);
            default -> null;
        };
        if (selection == null) return true;
        FixedWindowRateLimiter.Decision decision = limiter.acquire(selection.namespace(), selection.subject(), selection.policy());
        if (!decision.allowed()) throw new RateLimitExceededException(decision.retryAfterSeconds(), decision.resetAt());
        return true;
    }

    private Selection anonymous(String namespace, RateLimitProperties.Policy policy, HttpServletRequest request) {
        return new Selection(namespace, addresses.resolve(request), policy);
    }

    private Selection authenticated(String namespace, RateLimitProperties.Policy policy, HttpServletRequest request) {
        User user = (User) request.getAttribute(AuthInterceptor.USER);
        if (user == null) return null;
        return new Selection(namespace, Long.toString(user.getId()), policy);
    }

    private record Selection(String namespace, String subject, RateLimitProperties.Policy policy) {}
}
