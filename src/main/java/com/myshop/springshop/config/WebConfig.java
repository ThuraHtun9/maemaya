package com.myshop.springshop.config;

import java.time.Duration;
import java.util.Locale;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

import com.myshop.springshop.service.FileStorageService;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final FileStorageService fileStorageService;
    private final RateLimitInterceptor rateLimitInterceptor;

    public WebConfig(FileStorageService fileStorageService, RateLimitInterceptor rateLimitInterceptor) {
        this.fileStorageService = fileStorageService;
        this.rateLimitInterceptor = rateLimitInterceptor;
        
    }

    @Bean
    public LocaleResolver localeResolver() {
        CookieLocaleResolver resolver = new CookieLocaleResolver("SHOP_LOCALE");
        resolver.setDefaultLocale(new Locale("my"));
        resolver.setCookiePath("/");
        resolver.setCookieMaxAge(Duration.ofDays(365));
        return resolver;
    }

    @Bean
    public LocaleChangeInterceptor localeChangeInterceptor() {
        LocaleChangeInterceptor interceptor = new LocaleChangeInterceptor();
        interceptor.setParamName("lang");
        return interceptor;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Uploaded product images get a fresh UUID-prefixed filename per upload,
        // so a long cache lifetime is safe: a changed image is always a new URL.
        registry.addResourceHandler("/images/**")
                .addResourceLocations(
                        fileStorageService.getUploadPath().toUri().toString(),
                        "classpath:/static/images/"
                )
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic());
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(localeChangeInterceptor());
        registry.addInterceptor(rateLimitInterceptor)
        	.addPathPatterns(
        			"/order/manage",
        			"/order/manage/by-customer",
        			"/order/cancel",
        			"/order/reduce-item",
        			"/order/increase-item",
        			"/login");
    }
}
