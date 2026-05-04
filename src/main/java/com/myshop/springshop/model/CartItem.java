package com.myshop.springshop.model;

public record CartItem(
        Product product,
        int quantity,
        int subtotal
) {
}
