package com.myshop.springshop.model;

public record AdminOrderItemView(
        Long productId,
        String name,
        int quantity
) {
}
