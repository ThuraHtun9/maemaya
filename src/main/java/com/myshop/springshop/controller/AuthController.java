package com.myshop.springshop.controller;

import org.springframework.context.MessageSource;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Locale;

@Controller
public class AuthController {

    private final MessageSource messageSource;

    public AuthController(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    @GetMapping("/login")
    public String loginPage(
            @RequestParam(value = "error", required = false) String error,
            @RequestParam(value = "logout", required = false) String logout,
            @RequestParam(value = "lang", required = false) String lang,
            Authentication authentication,
            Model model
    ) {
        return renderLoginPage(error, logout, resolveLocale(lang), authentication, model);
    }

    @GetMapping("/login-my")
    public String loginPageMy(
            @RequestParam(value = "error", required = false) String error,
            @RequestParam(value = "logout", required = false) String logout,
            Authentication authentication,
            Model model
    ) {
        return renderLoginPage(error, logout, new Locale("my"), authentication, model);
    }

    @GetMapping("/login-en")
    public String loginPageEn(
            @RequestParam(value = "error", required = false) String error,
            @RequestParam(value = "logout", required = false) String logout,
            Authentication authentication,
            Model model
    ) {
        return renderLoginPage(error, logout, Locale.ENGLISH, authentication, model);
    }

    @GetMapping("/login-ja")
    public String loginPageJa(
            @RequestParam(value = "error", required = false) String error,
            @RequestParam(value = "logout", required = false) String logout,
            Authentication authentication,
            Model model
    ) {
        return renderLoginPage(error, logout, Locale.JAPANESE, authentication, model);
    }

    private String renderLoginPage(
            String error,
            String logout,
            Locale effectiveLocale,
            Authentication authentication,
            Model model
    ) {
        if (authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)) {
            return "redirect:/admin";
        }

        if (error != null) {
            model.addAttribute("error", messageSource.getMessage("login.error.invalid", null, effectiveLocale));
        }
        if (StringUtils.hasText(logout)) {
            model.addAttribute("logoutMessage", messageSource.getMessage("login.logout.success", null, effectiveLocale));
        }

        model.addAttribute("loginTitle", text("login.title", effectiveLocale));
        model.addAttribute("loginHeading", text("login.heading", effectiveLocale));
        model.addAttribute("loginDefaultUser", text("login.defaultUser", effectiveLocale));
        model.addAttribute("loginUsername", text("login.username", effectiveLocale));
        model.addAttribute("loginUsernamePlaceholder", text("login.username.placeholder", effectiveLocale));
        model.addAttribute("loginPassword", text("login.password", effectiveLocale));
        model.addAttribute("loginPasswordPlaceholder", text("login.password.placeholder", effectiveLocale));
        model.addAttribute("loginSubmit", text("login.submit", effectiveLocale));
        model.addAttribute("navShopBack", text("nav.shop.back", effectiveLocale));

        return "login";
    }

    private Locale resolveLocale(String lang) {
        if (!StringUtils.hasText(lang)) {
            return new Locale("my");
        }
        String normalized = lang.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "en" -> Locale.ENGLISH;
            case "ja" -> Locale.JAPANESE;
            default -> new Locale("my");
        };
    }

    private String text(String key, Locale locale) {
        return messageSource.getMessage(key, null, locale);
    }
}
