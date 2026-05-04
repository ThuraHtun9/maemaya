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
        return (category == null || category.isBlank()) ? "Other" : category;
    }

    public boolean hasImage() {
        return image != null && !image.isBlank();
    }
}
