package com.myshop.springshop.model;

public record Product(
        long id,
        String name,
        String category,
        int price,
        int stock,
        String image
) {
    public String displayCategory() {
        if (category == null) {
            return "Other";
        }
        String normalizedCategory = category.trim();
        return normalizedCategory.isBlank() ? "Other" : normalizedCategory;
    }

    public boolean hasImage() {
        return image != null && !image.isBlank();
    }
}
