package com.myshop.springshop.service;

import com.myshop.springshop.model.OrderRequest;
import com.myshop.springshop.repository.OrderRepository;
import com.myshop.springshop.repository.ProductRepository;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class OrderService {

    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final MessageSource messageSource;

    public OrderService(
            ProductRepository productRepository,
            OrderRepository orderRepository,
            MessageSource messageSource
    ) {
        this.productRepository = productRepository;
        this.orderRepository = orderRepository;
        this.messageSource = messageSource;
    }

    @Transactional
    public Optional<String> placeOrder(List<Long> cartProductIds, OrderRequest request) {
        Optional<String> mergeTargetOrderNumber = orderRepository.findTodayActiveOrderNumberForMerge(
                request.getPhone(),
                request.getCustomerName(),
                request.getDeliveryDate(),
                request.getDeliveryTime()
        );
        boolean appendToExistingOrder = mergeTargetOrderNumber.isPresent();
        int userOrderSeq = 0;
        String orderNumber;
        if (appendToExistingOrder) {
            orderNumber = mergeTargetOrderNumber.get();
        } else {
            userOrderSeq = orderRepository.findNextOrderSequenceByPhone(request.getPhone());
            orderNumber = buildOrderNumber(request.getPhone(), userOrderSeq);
        }

        int insertedRows = 0;
        for (Long productId : cartProductIds) {
            int updated = productRepository.decrementStockIfAvailable(productId);
            if (updated > 0) {
                if (appendToExistingOrder) {
                    int inserted = orderRepository.addItemToExistingOrder(productId, orderNumber, request.getPhone());
                    if (inserted > 0) {
                        insertedRows++;
                    } else {
                        productRepository.incrementStock(productId, 1);
                    }
                } else {
                    orderRepository.add(productId, request, userOrderSeq, orderNumber);
                    insertedRows++;
                }
            }
        }

        return insertedRows == 0 ? Optional.empty() : Optional.of(orderNumber);
    }

    @Transactional(readOnly = true)
    public Optional<ManagedOrder> findManagedOrder(String orderNumber, String phone) {
        return orderRepository.findOrderSummary(orderNumber, phone)
                .map(summary -> toManagedOrder(summary, orderRepository.findActiveOrderItems(orderNumber, phone)));
    }

    @Transactional(readOnly = true)
    public List<ManagedOrderLookup> findOrdersByCustomer(String customerName, String phone) {
        return orderRepository.findOrderSummariesByCustomer(customerName, phone, 20).stream()
                .map(summary -> new ManagedOrderLookup(
                        summary.orderNumber(),
                        summary.orderDate(),
                        summary.deliveryDate(),
                        summary.deliveryTime(),
                        summary.orderStatus()
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<OrderReceipt> findOrderReceipt(String orderNumber, String phone) {
        return orderRepository.findOrderSummary(orderNumber, phone)
                .map(summary -> {
                    List<OrderReceiptItem> items = orderRepository.findReceiptItems(orderNumber, phone).stream()
                            .map(row -> new OrderReceiptItem(
                                    row.productId(),
                                    row.productName(),
                                    row.productImage(),
                                    row.unitPrice(),
                                    row.quantity(),
                                    row.unitPrice() * row.quantity()
                            ))
                            .toList();

                    int totalPrice = orderRepository.findOrderTotal(orderNumber, phone);
                    return new OrderReceipt(
                            summary.orderNumber(),
                            summary.orderDate(),
                            summary.customerName(),
                            summary.phone(),
                            summary.deliveryDate(),
                            summary.deliveryTime(),
                            summary.orderStatus(),
                            items,
                            totalPrice
                    );
                });
    }

    @Transactional
    public CancelResult cancelOrder(String orderNumber, String phone) {
        Optional<OrderRepository.OrderSummary> summaryOpt = orderRepository.findOrderSummary(orderNumber, phone);
        if (summaryOpt.isEmpty()) {
            return new CancelResult(false, msg("order.cancel.notFound"));
        }

        ManagedOrder managedOrder = toManagedOrder(
                summaryOpt.get(),
                orderRepository.findActiveOrderItems(orderNumber, phone)
        );
        if (!managedOrder.cancelable()) {
            return new CancelResult(false, managedOrder.cancelStatusMessage());
        }

        List<OrderRepository.OrderProductCount> counts = orderRepository.findProductCountsForActiveOrder(orderNumber, phone);
        int canceledRows = orderRepository.cancelOrder(orderNumber, phone);
        if (canceledRows == 0) {
            return new CancelResult(false, msg("order.cancel.alreadyProcessed"));
        }

        for (OrderRepository.OrderProductCount count : counts) {
            productRepository.incrementStock(count.productId(), count.quantity());
        }

        return new CancelResult(true, msg("order.cancel.success"));
    }

    @Transactional
    public CancelResult reduceOneItem(String orderNumber, String phone, long productId) {
        Optional<OrderRepository.OrderSummary> summaryOpt = orderRepository.findOrderSummary(orderNumber, phone);
        if (summaryOpt.isEmpty()) {
            return new CancelResult(false, msg("order.reduce.notFound"));
        }

        ManagedOrder managedOrder = toManagedOrder(
                summaryOpt.get(),
                orderRepository.findActiveOrderItems(orderNumber, phone)
        );
        if (!managedOrder.cancelable()) {
            return new CancelResult(false, managedOrder.cancelStatusMessage());
        }

        Optional<Long> rowIdOpt = orderRepository.findSingleActiveOrderRowId(orderNumber, phone, productId);
        if (rowIdOpt.isEmpty()) {
            return new CancelResult(false, msg("order.reduce.itemNotFound"));
        }

        int updated = orderRepository.cancelSingleOrderRow(rowIdOpt.get());
        if (updated == 0) {
            return new CancelResult(false, msg("order.reduce.failed"));
        }

        productRepository.incrementStock(productId, 1);
        return new CancelResult(true, msg("order.reduce.success"));
    }

    @Transactional
    public CancelResult increaseOneItem(String orderNumber, String phone, long productId) {
        Optional<OrderRepository.OrderSummary> summaryOpt = orderRepository.findOrderSummary(orderNumber, phone);
        if (summaryOpt.isEmpty()) {
            return new CancelResult(false, msg("order.increase.notFound"));
        }

        ManagedOrder managedOrder = toManagedOrder(
                summaryOpt.get(),
                orderRepository.findActiveOrderItems(orderNumber, phone)
        );
        if (!managedOrder.cancelable()) {
            return new CancelResult(false, managedOrder.cancelStatusMessage());
        }

        boolean itemExists = managedOrder.items().stream().anyMatch(item -> item.productId() == productId);
        if (!itemExists) {
            return new CancelResult(false, msg("order.increase.itemNotFound"));
        }

        if (productRepository.findById(productId).isEmpty()) {
            return new CancelResult(false, msg("order.increase.itemNotFound"));
        }

        int stockUpdated = productRepository.decrementStockIfAvailable(productId);
        if (stockUpdated == 0) {
            return new CancelResult(false, msg("order.increase.outOfStock"));
        }

        int inserted = orderRepository.addItemToExistingOrder(productId, orderNumber, phone);
        if (inserted == 0) {
            productRepository.incrementStock(productId, 1);
            return new CancelResult(false, msg("order.increase.failed"));
        }

        return new CancelResult(true, msg("order.increase.success"));
    }

    @Transactional
    public CancelResult adminAddOneItem(String orderNumber, String phone, long productId) {
        Optional<OrderRepository.OrderSummary> summaryOpt = orderRepository.findOrderSummary(orderNumber, phone);
        if (summaryOpt.isEmpty()) {
            return new CancelResult(false, msg("admin.order.edit.notFound"));
        }
        if (!"ACTIVE".equals(summaryOpt.get().orderStatus())) {
            return new CancelResult(false, msg("admin.order.edit.onlyActive"));
        }
        if (productRepository.findById(productId).isEmpty()) {
            return new CancelResult(false, msg("admin.order.edit.productNotFound"));
        }

        int stockUpdated = productRepository.decrementStockIfAvailable(productId);
        if (stockUpdated == 0) {
            return new CancelResult(false, msg("admin.order.edit.outOfStock"));
        }

        int inserted = orderRepository.addItemToExistingOrder(productId, orderNumber, phone);
        if (inserted == 0) {
            productRepository.incrementStock(productId, 1);
            return new CancelResult(false, msg("admin.order.edit.addFailed"));
        }

        return new CancelResult(true, msg("admin.order.edit.addSuccess"));
    }

    @Transactional
    public CancelResult adminReduceOneItem(String orderNumber, String phone, long productId) {
        Optional<OrderRepository.OrderSummary> summaryOpt = orderRepository.findOrderSummary(orderNumber, phone);
        if (summaryOpt.isEmpty()) {
            return new CancelResult(false, msg("admin.order.edit.notFound"));
        }
        if (!"ACTIVE".equals(summaryOpt.get().orderStatus())) {
            return new CancelResult(false, msg("admin.order.edit.onlyActive"));
        }

        Optional<Long> rowIdOpt = orderRepository.findSingleActiveOrderRowId(orderNumber, phone, productId);
        if (rowIdOpt.isEmpty()) {
            return new CancelResult(false, msg("admin.order.edit.reduceNotFound"));
        }

        int updated = orderRepository.cancelSingleOrderRow(rowIdOpt.get());
        if (updated == 0) {
            return new CancelResult(false, msg("admin.order.edit.reduceFailed"));
        }

        productRepository.incrementStock(productId, 1);
        return new CancelResult(true, msg("admin.order.edit.reduceSuccess"));
    }

    private ManagedOrder toManagedOrder(
            OrderRepository.OrderSummary summary,
            List<OrderRepository.OrderItemSummary> itemSummaries
    ) {
        List<ManagedOrderItem> items = itemSummaries.stream()
                .map(item -> new ManagedOrderItem(item.productId(), item.productName(), item.quantity()))
                .toList();

        if ("READY".equals(summary.orderStatus())) {
            return new ManagedOrder(
                    summary.orderNumber(),
                    summary.orderDate(),
                    summary.customerName(),
                    summary.phone(),
                    summary.deliveryDate(),
                    summary.deliveryTime(),
                    summary.orderStatus(),
                    false,
                    msg("order.cancel.alreadyReady"),
                    items
            );
        }

        if ("DELIVERED".equals(summary.orderStatus())) {
            return new ManagedOrder(
                    summary.orderNumber(),
                    summary.orderDate(),
                    summary.customerName(),
                    summary.phone(),
                    summary.deliveryDate(),
                    summary.deliveryTime(),
                    summary.orderStatus(),
                    false,
                    msg("order.cancel.alreadyDelivered"),
                    items
            );
        }

        if (!"ACTIVE".equals(summary.orderStatus())) {
            return new ManagedOrder(
                    summary.orderNumber(),
                    summary.orderDate(),
                    summary.customerName(),
                    summary.phone(),
                    summary.deliveryDate(),
                    summary.deliveryTime(),
                    summary.orderStatus(),
                    false,
                    msg("order.cancel.alreadyCanceled"),
                    items
            );
        }

        if (summary.deliveryDate() == null || summary.deliveryDate().isBlank()) {
            return new ManagedOrder(
                    summary.orderNumber(),
                    summary.orderDate(),
                    summary.customerName(),
                    summary.phone(),
                    summary.deliveryDate(),
                    summary.deliveryTime(),
                    summary.orderStatus(),
                    false,
                    msg("order.cancel.noDeliveryDate"),
                    items
            );
        }

        LocalDate deliveryDate;
        try {
            deliveryDate = LocalDate.parse(summary.deliveryDate());
        } catch (DateTimeParseException ex) {
            return new ManagedOrder(
                    summary.orderNumber(),
                    summary.orderDate(),
                    summary.customerName(),
                    summary.phone(),
                    summary.deliveryDate(),
                    summary.deliveryTime(),
                    summary.orderStatus(),
                    false,
                    msg("order.cancel.invalidDeliveryDate"),
                    items
            );
        }

        LocalDate today = LocalDate.now();
        LocalDate deadlineDate = deliveryDate.minusDays(1);
        boolean cancelable = today.isBefore(deliveryDate);
        String statusMessage = cancelable
                ? msg("order.cancel.deadline", deadlineDate)
                : msg("order.cancel.deadlinePassed");

        return new ManagedOrder(
                summary.orderNumber(),
                summary.orderDate(),
                summary.customerName(),
                summary.phone(),
                summary.deliveryDate(),
                summary.deliveryTime(),
                summary.orderStatus(),
                cancelable,
                statusMessage,
                items
        );
    }

    private String buildOrderNumber(String phone, int userOrderSeq) {
        String digitsOnly = phone == null ? "" : phone.replaceAll("\\D", "");
        String suffix;
        if (digitsOnly.length() >= 4) {
            suffix = digitsOnly.substring(digitsOnly.length() - 4);
        } else if (!digitsOnly.isBlank()) {
            suffix = digitsOnly;
        } else {
            suffix = "0000";
        }

        return "MMY-" + suffix + "-" + String.format(Locale.ROOT, "%04d", userOrderSeq);
    }

    private String msg(String key, Object... args) {
        return messageSource.getMessage(key, args, LocaleContextHolder.getLocale());
    }

    public record ManagedOrder(
            String orderNumber,
            String orderDate,
            String customerName,
            String phone,
            String deliveryDate,
            String deliveryTime,
            String orderStatus,
            boolean cancelable,
            String cancelStatusMessage,
            List<ManagedOrderItem> items
    ) {
    }

    public record ManagedOrderItem(long productId, String productName, int quantity) {
    }

    public record ManagedOrderLookup(
            String orderNumber,
            String orderDate,
            String deliveryDate,
            String deliveryTime,
            String orderStatus
    ) {
    }

    public record CancelResult(boolean success, String message) {
    }

    public record OrderReceipt(
            String orderNumber,
            String orderDate,
            String customerName,
            String phone,
            String deliveryDate,
            String deliveryTime,
            String orderStatus,
            List<OrderReceiptItem> items,
            int totalPrice
    ) {
    }

    public record OrderReceiptItem(
            long productId,
            String productName,
            String productImage,
            int unitPrice,
            int quantity,
            int subtotal
    ) {
    }

}
