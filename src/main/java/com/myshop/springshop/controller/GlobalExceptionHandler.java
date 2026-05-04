package com.myshop.springshop.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Locale;

@ControllerAdvice(annotations = Controller.class)
public class GlobalExceptionHandler {

    private final MessageSource messageSource;

    public GlobalExceptionHandler(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    @ExceptionHandler({MaxUploadSizeExceededException.class, MultipartException.class})
    public String handleMultipartException(
            HttpServletRequest request,
            RedirectAttributes redirectAttributes,
            Locale locale
    ) {
        String path = request == null ? "" : request.getRequestURI();
        if (path != null && path.startsWith("/admin")) {
            redirectAttributes.addFlashAttribute(
                    "adminError",
                    messageSource.getMessage("admin.image.uploadFailed", null, locale)
            );
            return "redirect:/admin";
        }

        if (path != null && (path.startsWith("/request-product") || path.startsWith("/request-item"))) {
            redirectAttributes.addFlashAttribute(
                    "requestError",
                    messageSource.getMessage("request.error.uploadFailed", null, locale)
            );
            return "redirect:/request-item";
        }

        return "redirect:/";
    }
}
