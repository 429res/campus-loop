package edu.campusloop.config;
import edu.campusloop.auth.AuthInterceptor;
import edu.campusloop.ratelimit.RateLimitInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;
import java.nio.file.Path;
@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final AuthInterceptor auth; private final RateLimitInterceptor rateLimit; private final String[] origins; private final Path uploads;
    public WebConfig(AuthInterceptor auth,RateLimitInterceptor rateLimit,@Value("${campus.cors-origins}") String origins,@Value("${campus.upload-dir}") String uploads) {
        this.auth=auth; this.rateLimit=rateLimit; this.origins=origins.split(","); this.uploads=Path.of(uploads).toAbsolutePath().normalize();
    }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(auth).addPathPatterns("/api/**").order(0);
        registry.addInterceptor(rateLimit).addPathPatterns("/api/**").order(1);
    }
    @Override public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOrigins(origins).allowedMethods("GET","POST","PUT","PATCH","DELETE","OPTIONS")
            .allowedHeaders("Authorization","Content-Type").exposedHeaders("Retry-After","X-RateLimit-Reset").maxAge(3600);
    }
    @Override public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/**").addResourceLocations(uploads.toUri().toString()+"/").setCachePeriod(3600);
    }
}
