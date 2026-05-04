package com.myshop.springshop.model;

public record OrderRecord(
        String orderNumber,
        String orderDate,
        String customerName,
        String phone,
        String zipCode,
        String address,
        String addressDetail,
        String buildingName,
        String roomNumber,
        Long productId,
        String productName,
        int productPrice,
        String deliveryDate,
        String deliveryTime,
        String orderStatus
) {
}
