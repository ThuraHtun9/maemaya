package com.myshop.springshop.model;

import java.util.List;

public record AdminOrderView(
        String orderNumber,
        String orderDate,
        String customerName,
        String phone,
        String zipCode,
        String address,
        String addressDetail,
        String buildingName,
        String roomNumber,
        List<AdminOrderItemView> items,
        int totalPrice,
        String deliveryDate,
        String deliveryTime,
        String orderStatus
) {
}
