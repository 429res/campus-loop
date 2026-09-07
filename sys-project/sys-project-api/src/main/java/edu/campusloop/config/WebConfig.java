package edu.campusloop.config;
import edu.campusloop.auth.AuthInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;
import java.nio.file.Path;
@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final AuthInterceptor auth; private final String[] origins; private final Path uploads;
    public WebConfig(AuthInterceptor auth,@Value("${campus.cors-origins}") String origins,@Value("${campus.upload-dir}") String uploads) {
        this.auth=auth; this.origins=origins.split(","); this.uploads=Path.of(uploads).toAbsolutePath().normalize();
    }
    @Override public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(auth).addPathPatterns("/api/**"); }
    @Override public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOrigins(origins).allowedMethods("GET","POST","PUT","PATCH","DELETE","OPTIONS")
            .allowedHeaders("Authorization","Content-Type").maxAge(3600);
    }
    @Override public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/**").addResourceLocations(uploads.toUri().toString()+"/").setCachePeriod(3600);
    }
}
