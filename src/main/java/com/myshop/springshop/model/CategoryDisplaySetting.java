package com.myshop.springshop.model;

import java.util.Locale;

public record CategoryDisplaySetting(
        String categoryName,
        int displayOrder,
        String categoryNameEn,
        String categoryNameJa
) {
    public String displayNameFor(Locale locale) {
        String lang = locale == null ? "" : locale.getLanguage();
        if ("ja".equals(lang) && categoryNameJa != null && !categoryNameJa.isBlank()) {
            return categoryNameJa;
        }
        if ("en".equals(lang) && categoryNameEn != null && !categoryNameEn.isBlank()) {
            return categoryNameEn;
        }
        return categoryName;
    }
}
