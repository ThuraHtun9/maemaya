package com.myshop.springshop.controller;

import com.myshop.springshop.model.AdminOrderView;
import com.myshop.springshop.model.AdminOrderItemView;
import com.myshop.springshop.model.CategoryDisplaySetting;
import com.myshop.springshop.model.OrderRecord;
import com.myshop.springshop.model.Product;
import com.myshop.springshop.repository.OrderRepository;
import com.myshop.springshop.repository.ProductRequestRepository;
import com.myshop.springshop.repository.ProductRepository;
import com.myshop.springshop.service.FileStorageService;
import com.myshop.springshop.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.Comparator;

@Controller
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);
    private static final String TAB_PRODUCTS = "products";
    private static final String TAB_ORDERS = "orders";
    private static final String TAB_REQUESTS = "requests";

    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final ProductRequestRepository productRequestRepository;
    private final FileStorageService fileStorageService;
    private final OrderService orderService;
    private final MessageSource messageSource;

    public AdminController(
            ProductRepository productRepository,
            OrderRepository orderRepository,
            ProductRequestRepository productRequestRepository,
            FileStorageService fileStorageService,
            OrderService orderService,
            MessageSource messageSource
    ) {
        this.productRepository = productRepository;
        this.orderRepository = orderRepository;
        this.productRequestRepository = productRequestRepository;
        this.fileStorageService = fileStorageService;
        this.orderService = orderService;
        this.messageSource = messageSource;
    }

    @GetMapping("/admin")
    public String admin(
            @RequestParam(value = "orderNumber", required = false) String orderNumber,
            @RequestParam(value = "phone", required = false) String phone,
            @RequestParam(value = "tab", required = false) String tab,
            Model model,
            Locale locale
    ) {
        String activeTab = normalizeAdminTab(tab);
        model.addAttribute("activeTab", activeTab);
        try {
            List<Product> products = productRepository.findAll();
            List<Product> orderEditableProducts = products.stream()
                    .filter(product -> product.stock() > 0)
                    .toList();
            List<String> usedCategories = products.stream()
                    .map(Product::displayCategory)
                    .distinct()
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();

            Map<String, Integer> categoryOrderMap = productRepository.findCategoryDisplayOrderMap();
            LinkedHashSet<String> mergedCategories = new LinkedHashSet<>(categoryOrderMap.keySet());
            mergedCategories.addAll(usedCategories);
            List<String> categories = new ArrayList<>(mergedCategories);
            if (categories.isEmpty()) {
                categories = List.of("Coffee", "Tea", "Goods", "Other");
            }
            List<CategoryDisplaySetting> categorySettings = buildCategorySettings(categories, categoryOrderMap);
            Map<String, Long> categoryUsage = buildCategoryUsage(products);

            List<OrderRecord> orderRows = orderRepository.findAllWithProduct();
            String orderNumberFilter = normalize(orderNumber);
            String phoneFilter = normalizePhone(phone);
            List<AdminOrderView> orders = filterOrders(
                    summarizeOrders(orderRows),
                    orderNumberFilter,
                    phoneFilter
            );

            model.addAttribute("products", products);
            model.addAttribute("orders", orders);
            model.addAttribute("productRequests", productRequestRepository.findAll());
            model.addAttribute("categories", categories);
            model.addAttribute("categorySettings", categorySettings);
            model.addAttribute("categoryUsage", categoryUsage);
            model.addAttribute("orderSearchNumber", orderNumberFilter);
            model.addAttribute("orderSearchPhone", phoneFilter);
            model.addAttribute("orderEditableProducts", orderEditableProducts);

            if ((StringUtils.hasText(orderNumberFilter) || StringUtils.hasText(phoneFilter))
                    && orders.isEmpty()
                    && !model.containsAttribute("adminNotice")) {
                model.addAttribute(
                        "adminNotice",
                        messageSource.getMessage("admin.order.search.notFound", null, locale)
                );
            }
        } catch (Exception ex) {
            model.addAttribute("products", List.of());
            model.addAttribute("orders", List.of());
            model.addAttribute("productRequests", List.of());
            model.addAttribute("categories", List.of("Coffee", "Tea", "Goods", "Other"));
            model.addAttribute("categorySettings", List.of());
            model.addAttribute("categoryUsage", Map.of());
            model.addAttribute("orderSearchNumber", normalize(orderNumber));
            model.addAttribute("orderSearchPhone", normalizePhone(phone));
            model.addAttribute("orderEditableProducts", List.of());
            model.addAttribute("adminError", messageSource.getMessage("admin.error.db", null, locale));
        }

        return "admin";
    }

    @PostMapping("/admin/add")
    public String addProduct(
            @RequestParam("name") String name,
            @RequestParam("category") String category,
            @RequestParam("price") int price,
            @RequestParam("stock") int stock,
            @RequestParam(value = "image", required = false) MultipartFile image,
            @RequestParam(value = "tab", required = false) String tab,
            RedirectAttributes redirectAttributes,
            Locale locale
    ) {
        String safeName = normalize(name);
        String safeCategory = normalize(category);
        if (!StringUtils.hasText(safeName)) {
            redirectAttributes.addFlashAttribute(
                    "adminError",
                    messageSource.getMessage("admin.product.nameRequired", null, locale)
            );
            return redirectAdminWithTab(tab);
        }
        if (!StringUtils.hasText(safeCategory)) {
            redirectAttributes.addFlashAttribute(
                    "adminError",
                    messageSource.getMessage("admin.category.nameRequired", null, locale)
            );
            return redirectAdminWithTab(tab);
        }

        String imageName = "";
        boolean imageUploadFailed = false;
        try {
            imageName = fileStorageService.store(image);
        } catch (RuntimeException ex) {
            imageUploadFailed = true;
            log.warn("Failed to store image while adding product. name={}, category={}", safeName, safeCategory, ex);
        }

        try {
            productRepository.add(safeName, safeCategory, price, stock, imageName);
        } catch (RuntimeException ex) {
            if (StringUtils.hasText(imageName)) {
                fileStorageService.delete(imageName);
            }
            log.error("Failed to add product. name={}, category={}, image={}", safeName, safeCategory, imageName, ex);
            redirectAttributes.addFlashAttribute(
                    "adminError",
                    messageSource.getMessage("admin.product.addFailed", null, locale)
            );
            return redirectAdminWithTab(tab);
        }

        try {
            productRepository.ensureCategoryExists(safeCategory);
        } catch (RuntimeException ex) {
            log.warn("Failed to ensure category setting for category={}", safeCategory, ex);
        }

        redirectAttributes.addFlashAttribute(
                "adminNotice",
                messageSource.getMessage("admin.product.added", null, locale)
        );
        if (imageUploadFailed) {
            redirectAttributes.addFlashAttribute(
                    "adminError",
                    messageSource.getMessage("admin.image.uploadFailed", null, locale)
            );
        }
        return redirectAdminWithTab(tab);
    }

    @PostMapping("/admin/update/{productId}")
    public String updateProduct(
            @PathVariable long productId,
            @RequestParam("name") String name,
            @RequestParam("category") String category,
            @RequestParam("price") int price,
            @RequestParam("stock") int stock,
            @RequestParam(value = "image", required = false) MultipartFile image,
            @RequestParam(value = "removeImage", required = false, defaultValue = "false") boolean removeImage,
            @RequestParam(value = "tab", required = false) String tab,
            RedirectAttributes redirectAttributes,
            Locale locale
    ) {
        Product existing = productRepository.findById(productId).orElse(null);
        if (existing == null) {
            redirectAttributes.addFlashAttribute(
                    "adminError",
                    messageSource.getMessage("admin.product.notFound", null, locale)
            );
            return redirectAdminWithTab(tab);
        }

        String safeName = name == null ? "" : name.trim();
        String safeCategory = category == null ? "" : category.trim();
        if (!StringUtils.hasText(safeName)) {
            redirectAttributes.addFlashAttribute(
                    "adminError",
                    messageSource.getMessage("admin.product.nameRequired", null, locale)
            );
            return redirectAdminWithTab(tab);
        }
        if (!StringUtils.hasText(safeCategory)) {
            redirectAttributes.addFlashAttribute(
                    "adminError",
                    messageSource.getMessage("admin.category.nameRequired", null, locale)
            );
            return redirectAdminWithTab(tab);
        }

        String finalImage = existing.image();
        boolean hasNewImage = image != null && !image.isEmpty();

        if (hasNewImage) {
            String newImage;
            try {
                newImage = fileStorageService.store(image);
            } catch (RuntimeException ex) {
                log.warn("Failed to store replacement image. productId={}", productId, ex);
                redirectAttributes.addFlashAttribute(
                        "adminError",
                        messageSource.getMessage("admin.image.uploadFailed", null, locale)
                );
                return redirectAdminWithTab(tab);
            }
            if (existing.hasImage()) {
                fileStorageService.delete(existing.image());
            }
            finalImage = newImage;
        } else if (removeImage && existing.hasImage()) {
            fileStorageService.delete(existing.image());
            finalImage = "";
        }

        productRepository.updateProduct(productId, safeName, safeCategory, price, stock, finalImage);
        redirectAttributes.addFlashAttribute(
                "adminNotice",
                messageSource.getMessage("admin.product.updated", null, locale)
        );
        return redirectAdminWithTab(tab);
    }

    @PostMapping("/admin/delete/{productId}")
    public String deleteProduct(
            @PathVariable long productId,
            @RequestParam(value = "tab", required = false) String tab,
            RedirectAttributes redirectAttributes,
            Locale locale
    ) {
        productRepository.findById(productId).ifPresent(product -> {
            if (product.hasImage()) {
                fileStorageService.delete(product.image());
            }
        });
        productRepository.delete(productId);
        redirectAttributes.addFlashAttribute(
                "adminNotice",
                messageSource.getMessage("admin.product.deleted", null, locale)
        );
        return redirectAdminWithTab(tab);
    }

    @PostMapping("/admin/categories/add")
    public String addCategory(
            @RequestParam("categoryName") String categoryName,
            @RequestParam(value = "tab", required = false) String tab,
            RedirectAttributes redirectAttributes,
            Locale locale
    ) {
        String safeCategoryName = normalize(categoryName);
        if (!StringUtils.hasText(safeCategoryName)) {
            redirectAttributes.addFlashAttribute(
                    "adminError",
                    messageSource.getMessage("admin.category.nameRequired", null, locale)
            );
            return redirectAdminWithTab(tab);
        }

        try {
            productRepository.ensureCategoryExists(safeCategoryName);
            redirectAttributes.addFlashAttribute(
                    "adminNotice",
                    messageSource.getMessage("admin.category.added", null, locale)
            );
        } catch (RuntimeException ex) {
            redirectAttributes.addFlashAttribute(
                    "adminError",
                    messageSource.getMessage("admin.category.invalid", null, locale)
            );
        }
        return redirectAdminWithTab(tab);
    }

    @PostMapping("/admin/categories/update")
    public String updateCategory(
            @RequestParam("originalCategoryName") String originalCategoryName,
            @RequestParam("categoryName") String categoryName,
            @RequestParam("displayOrder") int displayOrder,
            @RequestParam(value = "tab", required = false) String tab,
            RedirectAttributes redirectAttributes,
            Locale locale
    ) {
        String safeOriginalName = normalize(originalCategoryName);
        String safeCategoryName = normalize(categoryName);
        int safeDisplayOrder = Math.max(displayOrder, 0);

        if (!StringUtils.hasText(safeOriginalName) || !StringUtils.hasText(safeCategoryName)) {
            redirectAttributes.addFlashAttribute(
                    "adminError",
                    messageSource.getMessage("admin.category.nameRequired", null, locale)
            );
            return redirectAdminWithTab(tab);
        }

        productRepository.updateCategory(safeOriginalName, safeCategoryName, safeDisplayOrder);
        redirectAttributes.addFlashAttribute(
                "adminNotice",
                messageSource.getMessage("admin.category.updated", null, locale)
        );
        return redirectAdminWithTab(tab);
    }

    @PostMapping("/admin/categories/delete")
    public String deleteCategory(
            @RequestParam("categoryName") String categoryName,
            @RequestParam(value = "tab", required = false) String tab,
            RedirectAttributes redirectAttributes,
            Locale locale
    ) {
        String safeCategoryName = normalize(categoryName);
        if (!StringUtils.hasText(safeCategoryName)) {
            redirectAttributes.addFlashAttribute(
                    "adminError",
                    messageSource.getMessage("admin.category.nameRequired", null, locale)
            );
            return redirectAdminWithTab(tab);
        }

        productRepository.deleteCategory(safeCategoryName);
        redirectAttributes.addFlashAttribute(
                "adminNotice",
                messageSource.getMessage("admin.category.deleted", null, locale)
        );
        return redirectAdminWithTab(tab);
    }

    @PostMapping("/admin/orders/{orderNumber}/ready")
    public String markOrderReady(
            @PathVariable String orderNumber,
            @RequestParam("phone") String phone,
            @RequestParam(value = "tab", required = false) String tab,
            RedirectAttributes redirectAttributes,
            Locale locale
    ) {
        int updated = orderRepository.markOrderReady(orderNumber, phone);
        if (updated > 0) {
            redirectAttributes.addFlashAttribute(
                    "adminNotice",
                    messageSource.getMessage("admin.order.readySuccess", null, locale)
            );
        } else {
            redirectAttributes.addFlashAttribute(
                    "adminError",
                    messageSource.getMessage("admin.order.transitionFailed", null, locale)
            );
        }
        return redirectAdminWithTab(tab);
    }

    @PostMapping("/admin/orders/{orderNumber}/delivered")
    public String markOrderDelivered(
            @PathVariable String orderNumber,
            @RequestParam("phone") String phone,
            @RequestParam(value = "tab", required = false) String tab,
            RedirectAttributes redirectAttributes,
            Locale locale
    ) {
        int updated = orderRepository.markOrderDelivered(orderNumber, phone);
        if (updated > 0) {
            redirectAttributes.addFlashAttribute(
                    "adminNotice",
                    messageSource.getMessage("admin.order.deliveredSuccess", null, locale)
            );
        } else {
            redirectAttributes.addFlashAttribute(
                    "adminError",
                    messageSource.getMessage("admin.order.transitionFailed", null, locale)
            );
        }
        return redirectAdminWithTab(tab);
    }

    @PostMapping("/admin/orders/{orderNumber}/delete")
    public String deleteOrder(
            @PathVariable String orderNumber,
            @RequestParam("phone") String phone,
            @RequestParam(value = "tab", required = false) String tab,
            RedirectAttributes redirectAttributes,
            Locale locale
    ) {
        int deleted = orderRepository.deleteOrder(orderNumber, phone);
        if (deleted > 0) {
            redirectAttributes.addFlashAttribute(
                    "adminNotice",
                    messageSource.getMessage("admin.order.deleteSuccess", null, locale)
            );
        } else {
            redirectAttributes.addFlashAttribute(
                    "adminError",
                    messageSource.getMessage("admin.order.deleteFailed", null, locale)
            );
        }
        return redirectAdminWithTab(tab);
    }

    @PostMapping("/admin/orders/{orderNumber}/items/add")
    public String addOrderItem(
            @PathVariable String orderNumber,
            @RequestParam("phone") String phone,
            @RequestParam("productId") long productId,
            @RequestParam(value = "tab", required = false) String tab,
            RedirectAttributes redirectAttributes
    ) {
        OrderService.CancelResult result = orderService.adminAddOneItem(
                normalize(orderNumber),
                normalizePhone(phone),
                productId
        );
        if (result.success()) {
            redirectAttributes.addFlashAttribute("adminNotice", result.message());
        } else {
            redirectAttributes.addFlashAttribute("adminError", result.message());
        }
        return redirectAdminWithTab(tab);
    }

    @PostMapping("/admin/orders/{orderNumber}/items/reduce")
    public String reduceOrderItem(
            @PathVariable String orderNumber,
            @RequestParam("phone") String phone,
            @RequestParam("productId") long productId,
            @RequestParam(value = "tab", required = false) String tab,
            RedirectAttributes redirectAttributes
    ) {
        OrderService.CancelResult result = orderService.adminReduceOneItem(
                normalize(orderNumber),
                normalizePhone(phone),
                productId
        );
        if (result.success()) {
            redirectAttributes.addFlashAttribute("adminNotice", result.message());
        } else {
            redirectAttributes.addFlashAttribute("adminError", result.message());
        }
        return redirectAdminWithTab(tab);
    }

    @PostMapping("/admin/requests/{requestId}/done")
    public String markRequestDone(
            @PathVariable long requestId,
            @RequestParam(value = "tab", required = false) String tab,
            RedirectAttributes redirectAttributes,
            Locale locale
    ) {
        int updated = productRequestRepository.markDone(requestId);
        if (updated > 0) {
            redirectAttributes.addFlashAttribute(
                    "adminNotice",
                    messageSource.getMessage("admin.requests.doneSuccess", null, locale)
            );
        }
        return redirectAdminWithTab(tab);
    }

    @PostMapping("/admin/requests/{requestId}/delete")
    public String deleteRequest(
            @PathVariable long requestId,
            @RequestParam(value = "tab", required = false) String tab,
            RedirectAttributes redirectAttributes,
            Locale locale
    ) {
        int deleted = productRequestRepository.delete(requestId);
        if (deleted > 0) {
            redirectAttributes.addFlashAttribute(
                    "adminNotice",
                    messageSource.getMessage("admin.requests.deleteSuccess", null, locale)
            );
        }
        return redirectAdminWithTab(tab);
    }

    private List<AdminOrderView> summarizeOrders(List<OrderRecord> orderRows) {
        Map<String, MutableOrderSummary> grouped = new LinkedHashMap<>();

        for (OrderRecord row : orderRows) {
            grouped.computeIfAbsent(row.orderNumber(), key -> new MutableOrderSummary(row))
                    .addProduct(row.productId(), row.productName(), row.orderStatus(), row.productPrice());
        }

        return grouped.values()
                .stream()
                .map(MutableOrderSummary::toView)
                .toList();
    }

    private List<AdminOrderView> filterOrders(
            List<AdminOrderView> orders,
            String orderNumberFilter,
            String phoneFilter
    ) {
        return orders.stream()
                .filter(order -> !StringUtils.hasText(orderNumberFilter)
                        || (order.orderNumber() != null
                        && order.orderNumber().toLowerCase(Locale.ROOT).contains(orderNumberFilter.toLowerCase(Locale.ROOT))))
                .filter(order -> !StringUtils.hasText(phoneFilter)
                        || (order.phone() != null && order.phone().contains(phoneFilter)))
                .toList();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalizePhone(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\D", "");
    }

    private String normalizeAdminTab(String tab) {
        if (!StringUtils.hasText(tab)) {
            return TAB_PRODUCTS;
        }
        String safeTab = tab.trim().toLowerCase(Locale.ROOT);
        return switch (safeTab) {
            case TAB_ORDERS -> TAB_ORDERS;
            case TAB_REQUESTS -> TAB_REQUESTS;
            default -> TAB_PRODUCTS;
        };
    }

    private String redirectAdminWithTab(String tab) {
        return "redirect:/admin?tab=" + normalizeAdminTab(tab);
    }

    private Map<String, Long> buildCategoryUsage(List<Product> products) {
        Map<String, Long> usage = new LinkedHashMap<>();
        for (Product product : products) {
            usage.merge(product.displayCategory(), 1L, Long::sum);
        }
        return usage;
    }

    private List<CategoryDisplaySetting> buildCategorySettings(
            List<String> categories,
            Map<String, Integer> categoryOrderMap
    ) {
        List<CategoryDisplaySetting> settings = new ArrayList<>();
        int fallbackOrder = 1000;

        for (String category : categories) {
            int order = categoryOrderMap.getOrDefault(category, fallbackOrder++);
            settings.add(new CategoryDisplaySetting(category, order));
        }

        settings.sort(
                Comparator.comparingInt(CategoryDisplaySetting::displayOrder)
                        .thenComparing(CategoryDisplaySetting::categoryName, String.CASE_INSENSITIVE_ORDER)
        );
        return settings;
    }

    private static final class MutableOrderSummary {
        private final String orderNumber;
        private final String orderDate;
        private final String customerName;
        private final String phone;
        private final String zipCode;
        private final String address;
        private final String addressDetail;
        private final String buildingName;
        private final String roomNumber;
        private final String deliveryDate;
        private final String deliveryTime;
        private boolean hasActive;
        private boolean hasReady;
        private boolean hasDelivered;
        private boolean hasCanceled;
        private final Map<String, MutableOrderItem> productCounts = new LinkedHashMap<>();
        private int totalPrice;

        private MutableOrderSummary(OrderRecord row) {
            this.orderNumber = row.orderNumber();
            this.orderDate = row.orderDate();
            this.customerName = row.customerName();
            this.phone = row.phone();
            this.zipCode = row.zipCode();
            this.address = row.address();
            this.addressDetail = row.addressDetail();
            this.buildingName = row.buildingName();
            this.roomNumber = row.roomNumber();
            this.deliveryDate = row.deliveryDate();
            this.deliveryTime = row.deliveryTime();
            observeStatus(row.orderStatus());
        }

        private MutableOrderSummary addProduct(Long productId, String productName, String rowStatus, int productPrice) {
            observeStatus(rowStatus);
            if (!"CANCELED".equals(rowStatus)) {
                String safeName = (productName == null || productName.isBlank()) ? "Deleted product" : productName;
                String itemKey = productId == null ? "name:" + safeName : "id:" + productId;
                productCounts.computeIfAbsent(itemKey, key -> new MutableOrderItem(productId, safeName)).increment();
                totalPrice += Math.max(productPrice, 0);
            }
            return this;
        }

        private void observeStatus(String rowStatus) {
            if ("ACTIVE".equals(rowStatus)) {
                hasActive = true;
                return;
            }
            if ("READY".equals(rowStatus)) {
                hasReady = true;
                return;
            }
            if ("DELIVERED".equals(rowStatus)) {
                hasDelivered = true;
                return;
            }
            hasCanceled = true;
        }

        private String resolveOrderStatus() {
            if (hasActive) {
                return "ACTIVE";
            }
            if (hasReady) {
                return "READY";
            }
            if (hasDelivered) {
                return "DELIVERED";
            }
            if (hasCanceled) {
                return "CANCELED";
            }
            return "CANCELED";
        }

        private AdminOrderView toView() {
            List<AdminOrderItemView> items = new ArrayList<>();
            for (MutableOrderItem item : productCounts.values()) {
                items.add(item.toView());
            }

            return new AdminOrderView(
                    orderNumber,
                    orderDate,
                    customerName,
                    phone,
                    zipCode,
                    address,
                    addressDetail,
                    buildingName,
                    roomNumber,
                    items,
                    totalPrice,
                    deliveryDate,
                    deliveryTime,
                    resolveOrderStatus()
            );
        }
    }

    private static final class MutableOrderItem {
        private final Long productId;
        private final String name;
        private int quantity;

        private MutableOrderItem(Long productId, String name) {
            this.productId = productId;
            this.name = name;
            this.quantity = 0;
        }

        private void increment() {
            quantity++;
        }

        private AdminOrderItemView toView() {
            return new AdminOrderItemView(productId, name, quantity);
        }
    }
}
