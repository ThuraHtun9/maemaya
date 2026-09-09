package com.myshop.springshop.controller;

import com.myshop.springshop.model.CartItem;
import com.myshop.springshop.model.CategoryDisplaySetting;
import com.myshop.springshop.model.OrderRequest;
import com.myshop.springshop.model.PaginationView;
import com.myshop.springshop.model.Product;
import com.myshop.springshop.repository.ProductRequestRepository;
import com.myshop.springshop.repository.ProductRepository;
import com.myshop.springshop.service.CartService;
import com.myshop.springshop.service.FileStorageService;
import com.myshop.springshop.service.OrderService;
import com.myshop.springshop.service.PostalCodeLookupService;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

@Controller
public class ShopController {

    private static final Logger log = LoggerFactory.getLogger(ShopController.class);
    private static final ZoneId ORDER_TIME_ZONE = ZoneId.of("Asia/Tokyo");
    private static final int SHOP_PAGE_SIZE = 12;

    private final ProductRepository productRepository;
    private final ProductRequestRepository productRequestRepository;
    private final CartService cartService;
    private final FileStorageService fileStorageService;
    private final OrderService orderService;
    private final PostalCodeLookupService postalCodeLookupService;
    private final MessageSource messageSource;

    public ShopController(
            ProductRepository productRepository,
            ProductRequestRepository productRequestRepository,
            CartService cartService,
            FileStorageService fileStorageService,
            OrderService orderService,
            PostalCodeLookupService postalCodeLookupService,
            MessageSource messageSource
    ) {
        this.productRepository = productRepository;
        this.productRequestRepository = productRequestRepository;
        this.cartService = cartService;
        this.fileStorageService = fileStorageService;
        this.orderService = orderService;
        this.postalCodeLookupService = postalCodeLookupService;
        this.messageSource = messageSource;
    }

    @GetMapping("/")
    public String index(
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "q", required = false) String q,
            Model model,
            HttpSession session,
            Authentication authentication,
            Locale locale
    ) {
        String searchQuery = q == null ? "" : q.trim();
        List<Product> products = productRepository.findAll();
        if(!searchQuery.isEmpty()) {
            String needle = searchQuery.toLowerCase(Locale.ROOT);
            products = products.stream()
                    .filter(product -> product.name().toLowerCase(Locale.ROOT).contains(needle)
                            || product.displayCategory().toLowerCase(Locale.ROOT).contains(needle))
                    .toList();
        }
        Map<String, List<Product>> grouped = new LinkedHashMap<>();
        Map<Long, Integer> cartItemCounts = new LinkedHashMap<>();

        for (Long productId : cartService.getCart(session)) {
            cartItemCounts.put(productId, cartItemCounts.getOrDefault(productId, 0) + 1);
        }

        for (Product product : products) {
            grouped
                    .computeIfAbsent(product.displayCategory(), key -> new ArrayList<>())
                    .add(product);
        }

        List<CategoryDisplaySetting> categorySettingsFromDb = productRepository.findCategorySettings();
        Map<String, CategoryDisplaySetting> categorySettingsByName = categorySettingsFromDb.stream()
                .collect(Collectors.toMap(CategoryDisplaySetting::categoryName, setting -> setting, (a, b) -> a));
        List<String> sortedCategories = new ArrayList<>(grouped.keySet());
        sortedCategories.sort(
                Comparator.comparingInt((String categoryName) -> {
                            CategoryDisplaySetting setting = categorySettingsByName.get(categoryName);
                            return setting == null ? 9999 : setting.displayOrder();
                        })
                        .thenComparing(String.CASE_INSENSITIVE_ORDER)
        );
        Map<String, String> categoryDisplayNames = new LinkedHashMap<>();
        for (String categoryName : sortedCategories) {
            CategoryDisplaySetting setting = categorySettingsByName.get(categoryName);
            categoryDisplayNames.put(categoryName, setting == null ? categoryName : setting.displayNameFor(locale));
        }

        String activeCategory = resolveActiveCategory(category, sortedCategories);
        List<Product> orderedProducts = new ArrayList<>();
        for (String categoryName : sortedCategories) {
            if ("all".equals(activeCategory) || activeCategory.equals(categoryName)) {
                orderedProducts.addAll(grouped.get(categoryName));
            }
        }

        PaginationView<Product> shopPagination = paginate(orderedProducts, page, SHOP_PAGE_SIZE);
        Map<String, Integer> totalCategoryCounts = new LinkedHashMap<>();
        for (String categoryName : sortedCategories) {
            totalCategoryCounts.put(categoryName, grouped.get(categoryName).size());
        }

        Map<String, List<Product>> productsByCategory = new LinkedHashMap<>();
        for (Product product : shopPagination.items()) {
            productsByCategory
                    .computeIfAbsent(product.displayCategory(), key -> new ArrayList<>())
                    .add(product);
        }

        model.addAttribute("productsByCategory", productsByCategory);
        model.addAttribute("categoryTotalCounts", totalCategoryCounts);
        model.addAttribute("shopCategories", sortedCategories);
        model.addAttribute("categoryDisplayNames", categoryDisplayNames);
        model.addAttribute("activeCategory", activeCategory);
        model.addAttribute("shopPagination", shopPagination);
        model.addAttribute("searchQuery", searchQuery);
        model.addAttribute("cartCount", cartService.count(session));
        model.addAttribute("cartItemCounts", cartItemCounts);
        boolean isAdmin = authentication != null
                && authentication.getAuthorities().stream()
                .anyMatch(auth -> "ROLE_ADMIN".equals(auth.getAuthority()));
        model.addAttribute("isAdmin", isAdmin);
        return "index";
    }

    @GetMapping("/cart/add/{productId}")
    public String addToCart(
            @PathVariable long productId,
            HttpSession session,
            RedirectAttributes redirectAttributes,
            Locale locale
    ) {
        CartAddResult result = addProductToCart(session, productId, 1);
        if (result.success()) {
            redirectAttributes.addFlashAttribute(
                    "cartSuccess",
                    messageSource.getMessage("index.cart.added", new Object[]{result.added()}, locale)
            );
        } else {
            redirectAttributes.addFlashAttribute("cartError", resolveCartAddError(result.errorCode(), locale));
        }
        return "redirect:/";
    }

    @PostMapping("/cart/add")
    @ResponseBody
    public Map<String, Object> addToCartAjax(
            @RequestParam("productId") long productId,
            @RequestParam(value = "quantity", required = false) String quantity,
            HttpSession session,
            Locale locale
    ) {
        int requestedQuantity = parseQuantity(quantity, 99);
        CartAddResult result = addProductToCart(session, productId, requestedQuantity);
        if (!result.success()) {
            return Map.of(
                    "success", false,
                    "cartCount", cartService.count(session),
                    "productCount", cartService.countByProduct(session, productId),
                    "message", resolveCartAddError(result.errorCode(), locale)
            );
        }
        return Map.of(
                "success", true,
                "added", result.added(),
                "cartCount", cartService.count(session),
                "productCount", result.productCount(),
                "message", messageSource.getMessage("index.cart.added", new Object[]{result.added()}, locale)
        );
    }

    @PostMapping("/cart/remove")
    @ResponseBody
    public Map<String, Object> removeFromCartAjax(
            @RequestParam("productId") long productId,
            @RequestParam(value = "quantity", required = false) String quantity,
            HttpSession session,
            Locale locale
    ) {
        int requestedQuantity = parseQuantity(quantity, 99);
        int removed = cartService.removeMany(session, productId, requestedQuantity);
        if (removed <= 0) {
            return Map.of(
                    "success", false,
                    "cartCount", cartService.count(session),
                    "productCount", cartService.countByProduct(session, productId),
                    "message", messageSource.getMessage("index.cart.removeUnavailable", null, locale)
            );
        }
        return Map.of(
                "success", true,
                "removed", removed,
                "cartCount", cartService.count(session),
                "productCount", cartService.countByProduct(session, productId)
        );
    }

    @PostMapping("/cart/add-selected")
    public String addSelectedToCart(
            @RequestParam(value = "productIds", required = false) List<Long> productIds,
            @RequestParam Map<String, String> params,
            HttpSession session,
            RedirectAttributes redirectAttributes,
            Locale locale
    ) {
        if (productIds == null || productIds.isEmpty()) {
            redirectAttributes.addFlashAttribute(
                    "cartError",
                    messageSource.getMessage("index.cart.selectionRequired", null, locale)
            );
            return "redirect:/";
        }

        int addedCount = 0;
        for (Long productId : productIds) {
            int quantity = parseQuantity(params.get("qty_" + productId));
            CartAddResult result = addProductToCart(session, productId, quantity);
            addedCount += result.added();
        }

        if (addedCount == 0) {
            redirectAttributes.addFlashAttribute(
                    "cartError",
                    messageSource.getMessage("index.cart.noneAdded", null, locale)
            );
        } else {
            redirectAttributes.addFlashAttribute(
                    "cartSuccess",
                    messageSource.getMessage("index.cart.addedSelected", new Object[]{addedCount}, locale)
            );
        }

        return "redirect:/";
    }

    @GetMapping("/cart")
    public String viewCart(Model model, HttpSession session) {
        List<Long> cartIds = cartService.getCart(session);
        Map<Long, Integer> counts = new LinkedHashMap<>();

        for (Long productId : cartIds) {
            counts.put(productId, counts.getOrDefault(productId, 0) + 1);
        }

        List<CartItem> cartItems = new ArrayList<>();
        int totalPrice = 0;

        for (Map.Entry<Long, Integer> entry : counts.entrySet()) {
            Optional<Product> productOpt = productRepository.findById(entry.getKey());
            if (productOpt.isEmpty()) {
                continue;
            }

            Product product = productOpt.get();
            int quantity = entry.getValue();
            int subtotal = product.price() * quantity;
            totalPrice += subtotal;
            cartItems.add(new CartItem(product, quantity, subtotal));
        }

        model.addAttribute("cartItems", cartItems);
        model.addAttribute("totalPrice", totalPrice);
        return "cart";
    }

    @GetMapping("/cart/delete/{productId}")
    public String deleteFromCart(@PathVariable long productId, HttpSession session) {
        cartService.removeOne(session, productId);
        return "redirect:/cart";
    }

    @GetMapping("/checkout")
    public String checkout(Model model, HttpSession session) {
        List<Long> cartIds = cartService.getCart(session);
        if (cartIds.isEmpty()) {
            return "redirect:/";
        }

        List<Product> products = new ArrayList<>();
        int totalPrice = 0;

        for (Long productId : cartIds) {
            Optional<Product> productOpt = productRepository.findById(productId);
            if (productOpt.isEmpty()) {
                continue;
            }

            Product product = productOpt.get();
            products.add(product);
            totalPrice += product.price();
        }

        model.addAttribute("products", products);
        model.addAttribute("totalPrice", totalPrice);
        model.addAttribute("minDeliveryDate", earliestDeliveryDate().toString());
        if (!model.containsAttribute("orderRequest")) {
            model.addAttribute("orderRequest", new OrderRequest());
        }
        return "order_form";
    }

    @PostMapping("/order/confirm")
    public String orderConfirm(
            @ModelAttribute OrderRequest orderRequest,
            HttpSession session,
            RedirectAttributes redirectAttributes,
            Locale locale
    ) {
        List<Long> cartIds = new ArrayList<>(cartService.getCart(session));
        if (cartIds.isEmpty()) {
            return "redirect:/";
        }

        OrderRequest safeOrderRequest = normalizeOrderRequest(orderRequest);
        String validationError = validateOrderRequest(safeOrderRequest, locale);
        if (validationError != null) {
            redirectAttributes.addFlashAttribute("orderError", validationError);
            redirectAttributes.addFlashAttribute("orderRequest", safeOrderRequest);
            return "redirect:/checkout";
        }

        try {
            Optional<String> orderNumberOpt = orderService.placeOrder(cartIds, safeOrderRequest);
            if (orderNumberOpt.isEmpty()) {
                redirectAttributes.addFlashAttribute(
                        "orderError",
                        messageSource.getMessage("order.form.error.outOfStock", null, locale)
                );
                redirectAttributes.addFlashAttribute("orderRequest", safeOrderRequest);
                return "redirect:/checkout";
            }

            String orderNumber = orderNumberOpt.get();
            cartService.clear(session);
            redirectAttributes.addAttribute("orderNumber", orderNumber);
            redirectAttributes.addAttribute("phone", safeOrderRequest.getPhone());
            return "redirect:/order/success";
        } catch (Exception ex) {
            log.error("Order confirmation failed unexpectedly", ex);
            redirectAttributes.addFlashAttribute(
                    "orderError",
                    messageSource.getMessage("order.form.error.generic", null, locale)
            );
            redirectAttributes.addFlashAttribute("orderRequest", safeOrderRequest);
            return "redirect:/checkout";
        }
    }

    @GetMapping("/order/success")
    public String orderSuccess(
            @RequestParam("orderNumber") String orderNumber,
            @RequestParam("phone") String phone,
            Model model,
            Locale locale
    ) {
        String safeOrderNumber = normalize(orderNumber);
        String safePhone = normalizePhone(phone);
        if (!StringUtils.hasText(safeOrderNumber) || !StringUtils.hasText(safePhone)) {
            return "redirect:/";
        }

        model.addAttribute("orderNumber", safeOrderNumber);
        model.addAttribute("orderPhone", safePhone);

        orderService.findOrderReceipt(safeOrderNumber, safePhone)
                .ifPresentOrElse(
                        receipt -> model.addAttribute("receipt", receipt),
                        () -> model.addAttribute(
                                "receiptError",
                                messageSource.getMessage("order.success.lookupError", null, locale)
                        )
                );

        return "order_success";
    }

    @GetMapping("/request-item")
    public String requestItemPage() {
        return "request_item";
    }

    @PostMapping("/request-product")
    public String requestProduct(
            @RequestParam("requesterName") String requesterName,
            @RequestParam("contact") String contact,
            @RequestParam(value = "productName", required = false) String productName,
            @RequestParam(value = "note", required = false) String note,
            @RequestParam(value = "referenceImage", required = false) MultipartFile referenceImage,
            RedirectAttributes redirectAttributes,
            Locale locale
    ) {
        String safeRequesterName = requesterName == null ? "" : requesterName.trim();
        String safeContact = contact == null ? "" : contact.trim();
        String safeProductName = productName == null ? "" : productName.trim();
        String safeNote = note == null ? "" : note.trim();

        if (!StringUtils.hasText(safeRequesterName) || !StringUtils.hasText(safeContact)) {
            redirectAttributes.addFlashAttribute(
                    "requestError",
                    messageSource.getMessage("request.error.needContact", null, locale)
            );
            return "redirect:/request-item";
        }

        boolean hasName = StringUtils.hasText(safeProductName);
        boolean hasImage = referenceImage != null && !referenceImage.isEmpty();
        if (!hasName && !hasImage) {
            redirectAttributes.addFlashAttribute(
                    "requestError",
                    messageSource.getMessage("request.error.needNameOrImage", null, locale)
            );
            return "redirect:/request-item";
        }

        String storedImage = "";
        if (hasImage) {
            try {
                storedImage = fileStorageService.store(referenceImage);
            } catch (RuntimeException ex) {
                log.warn("Failed to store request reference image. requester={}, contact={}", safeRequesterName, safeContact, ex);
                redirectAttributes.addFlashAttribute(
                        "requestError",
                        messageSource.getMessage("request.error.uploadFailed", null, locale)
                );
                return "redirect:/request-item";
            }
        }

        try {
            productRequestRepository.add(
                    safeRequesterName,
                    safeContact,
                    safeProductName,
                    safeNote,
                    storedImage
            );
        } catch (RuntimeException ex) {
            if (StringUtils.hasText(storedImage)) {
                fileStorageService.delete(storedImage);
            }
            log.error("Failed to save product request. requester={}, contact={}", safeRequesterName, safeContact, ex);
            redirectAttributes.addFlashAttribute(
                    "requestError",
                    messageSource.getMessage("request.error.submitFailed", null, locale)
            );
            return "redirect:/request-item";
        }

        redirectAttributes.addFlashAttribute(
                "requestSuccess",
                messageSource.getMessage("request.success", null, locale)
        );
        return "redirect:/request-item";
    }

    @GetMapping("/api/postal-lookup")
    @ResponseBody
    public Map<String, Object> postalLookup(
            @RequestParam("zipCode") String zipCode,
            Locale locale
    ) {
        String normalizedZipCode = postalCodeLookupService.normalize(zipCode);
        if (!postalCodeLookupService.isValid(normalizedZipCode)) {
            return Map.of(
                    "success", false,
                    "message", messageSource.getMessage("order.form.postalLookup.invalid", null, locale)
            );
        }

        return postalCodeLookupService.findAddressByZipCode(normalizedZipCode)
                .<Map<String, Object>>map(address -> Map.of(
                        "success", true,
                        "zipCode", normalizedZipCode,
                        "address", address,
                        "message", messageSource.getMessage("order.form.postalLookup.success", null, locale)
                ))
                .orElseGet(() -> Map.of(
                        "success", false,
                        "message", messageSource.getMessage("order.form.postalLookup.notFound", null, locale)
                ));
    }

    @GetMapping("/order/manage")
    public String orderManage(
            @RequestParam(value = "orderNumber", required = false) String orderNumber,
            @RequestParam(value = "phone", required = false) String phone,
            @RequestParam(value = "customerName", required = false) String customerName,
            Model model,
            Locale locale
    ) {
        String safeOrderNumber = normalize(orderNumber);
        String safePhone = normalizePhone(phone);
        String safeCustomerName = normalize(customerName);
        model.addAttribute("orderNumber", safeOrderNumber);
        model.addAttribute("phone", safePhone);
        model.addAttribute("customerName", safeCustomerName);

        if (StringUtils.hasText(safeOrderNumber) && StringUtils.hasText(safePhone)) {
            orderService.findManagedOrder(safeOrderNumber, safePhone)
                    .ifPresentOrElse(
                            managedOrder -> model.addAttribute("managedOrder", managedOrder),
                            () -> model.addAttribute("lookupError", messageSource.getMessage("order.manage.lookupError", null, locale))
                    );
        } else if (StringUtils.hasText(safeCustomerName) && StringUtils.hasText(safePhone)) {
            List<OrderService.ManagedOrderLookup> matchedOrders = orderService.findOrdersByCustomer(safeCustomerName, safePhone);
            model.addAttribute("matchedOrders", matchedOrders);
            if (matchedOrders.isEmpty()) {
                model.addAttribute("lookupError", messageSource.getMessage("order.manage.customerLookupError", null, locale));
            }
        }
        return "order_manage";
    }

    @PostMapping("/order/manage/by-customer")
    public String orderManageCustomerSearch(
            @RequestParam("customerName") String customerName,
            @RequestParam("phone") String phone,
            RedirectAttributes redirectAttributes
    ) {
        redirectAttributes.addAttribute("customerName", normalize(customerName));
        redirectAttributes.addAttribute("phone", normalizePhone(phone));
        return "redirect:/order/manage";
    }

    @PostMapping("/order/cancel")
    public String cancelOrder(
            @RequestParam("orderNumber") String orderNumber,
            @RequestParam("phone") String phone,
            @RequestParam(value = "customerName", required = false) String customerName,
            RedirectAttributes redirectAttributes
    ) {
        String safeOrderNumber = normalize(orderNumber);
        String safePhone = normalizePhone(phone);
        String safeCustomerName = normalize(customerName);
        OrderService.CancelResult result = orderService.cancelOrder(safeOrderNumber, safePhone);
        redirectAttributes.addFlashAttribute("manageMessage", result.message());
        redirectAttributes.addFlashAttribute("manageSuccess", result.success());
        redirectAttributes.addAttribute("orderNumber", safeOrderNumber);
        redirectAttributes.addAttribute("phone", safePhone);
        if (StringUtils.hasText(safeCustomerName)) {
            redirectAttributes.addAttribute("customerName", safeCustomerName);
        }
        return "redirect:/order/manage";
    }

    @PostMapping("/order/reduce-item")
    public String reduceOrderItem(
            @RequestParam("orderNumber") String orderNumber,
            @RequestParam("phone") String phone,
            @RequestParam("productId") long productId,
            @RequestParam(value = "customerName", required = false) String customerName,
            RedirectAttributes redirectAttributes
    ) {
        String safeOrderNumber = normalize(orderNumber);
        String safePhone = normalizePhone(phone);
        String safeCustomerName = normalize(customerName);
        OrderService.CancelResult result = orderService.reduceOneItem(safeOrderNumber, safePhone, productId);
        redirectAttributes.addFlashAttribute("manageMessage", result.message());
        redirectAttributes.addFlashAttribute("manageSuccess", result.success());
        redirectAttributes.addAttribute("orderNumber", safeOrderNumber);
        redirectAttributes.addAttribute("phone", safePhone);
        if (StringUtils.hasText(safeCustomerName)) {
            redirectAttributes.addAttribute("customerName", safeCustomerName);
        }
        return "redirect:/order/manage";
    }

    @PostMapping("/order/increase-item")
    public String increaseOrderItem(
            @RequestParam("orderNumber") String orderNumber,
            @RequestParam("phone") String phone,
            @RequestParam("productId") long productId,
            @RequestParam(value = "customerName", required = false) String customerName,
            RedirectAttributes redirectAttributes
    ) {
        String safeOrderNumber = normalize(orderNumber);
        String safePhone = normalizePhone(phone);
        String safeCustomerName = normalize(customerName);
        OrderService.CancelResult result = orderService.increaseOneItem(safeOrderNumber, safePhone, productId);
        redirectAttributes.addFlashAttribute("manageMessage", result.message());
        redirectAttributes.addFlashAttribute("manageSuccess", result.success());
        redirectAttributes.addAttribute("orderNumber", safeOrderNumber);
        redirectAttributes.addAttribute("phone", safePhone);
        if (StringUtils.hasText(safeCustomerName)) {
            redirectAttributes.addAttribute("customerName", safeCustomerName);
        }
        return "redirect:/order/manage";
    }

    private int parseQuantity(String raw) {
        return parseQuantity(raw, 20);
    }

    private int parseQuantity(String raw, int max) {
        if (!StringUtils.hasText(raw)) {
            return 1;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < 1) {
                return 1;
            }
            return Math.min(value, Math.max(max, 1));
        } catch (NumberFormatException ex) {
            return 1;
        }
    }

    private CartAddResult addProductToCart(HttpSession session, long productId, int requestedQuantity) {
        Optional<Product> productOpt = productRepository.findById(productId);
        if (productOpt.isEmpty()) {
            return CartAddResult.failure("notFound");
        }

        Product product = productOpt.get();
        if (product.stock() <= 0) {
            return CartAddResult.failure("outOfStock");
        }

        int safeRequestedQuantity = Math.max(1, requestedQuantity);
        int inCartCount = cartService.countByProduct(session, productId);
        int availableToAdd = Math.max(0, product.stock() - inCartCount);
        if (availableToAdd <= 0) {
            return CartAddResult.failure("stockLimitReached");
        }

        int added = cartService.addMany(session, productId, Math.min(safeRequestedQuantity, availableToAdd));
        int productCount = cartService.countByProduct(session, productId);
        return CartAddResult.success(added, productCount);
    }

    private String resolveCartAddError(String errorCode, Locale locale) {
        if ("stockLimitReached".equals(errorCode)) {
            return messageSource.getMessage("index.cart.limitReached", null, locale);
        }
        return messageSource.getMessage("index.cart.noneAdded", null, locale);
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

    private boolean isValidPhone(String normalizedPhone) {
        return normalizedPhone.matches("\\d{10,11}");
    }

    private String validateOrderRequest(OrderRequest request, Locale locale) {
        if (!StringUtils.hasText(request.getCustomerName())
                || !StringUtils.hasText(request.getAddress())
                || !StringUtils.hasText(request.getPhone())
                || !StringUtils.hasText(request.getDeliveryDate())) {
            return messageSource.getMessage("order.form.error.missingRequired", null, locale);
        }
        if (!isValidPhone(request.getPhone())) {
            return messageSource.getMessage("order.form.error.invalidPhone", null, locale);
        }
        LocalDate requestedDeliveryDate;
        try {
            requestedDeliveryDate = LocalDate.parse(request.getDeliveryDate());
        } catch (DateTimeParseException ex) {
            return messageSource.getMessage("order.form.error.invalidDeliveryDate", null, locale);
        }
        LocalDate minimumAllowedDate = earliestDeliveryDate();
        if (requestedDeliveryDate.isBefore(minimumAllowedDate)) {
            return messageSource.getMessage(
                    "order.form.error.invalidDeliveryDate",
                    new Object[]{minimumAllowedDate},
                    locale
            );
        }
        return null;
    }

    private LocalDate earliestDeliveryDate() {
        return LocalDate.now(ORDER_TIME_ZONE).plusDays(1);
    }

    private <T> PaginationView<T> paginate(List<T> items, Integer requestedPage, int pageSize) {
        int safePageSize = Math.max(1, pageSize);
        int totalItems = items.size();
        int totalPages = Math.max(1, (int) Math.ceil((double) totalItems / safePageSize));
        int currentPage = requestedPage == null ? 1 : requestedPage;
        currentPage = Math.max(1, Math.min(currentPage, totalPages));

        if (totalItems == 0) {
            return new PaginationView<>(List.of(), 1, 1, safePageSize, 0, 0, 0);
        }

        int fromIndex = (currentPage - 1) * safePageSize;
        int toIndex = Math.min(fromIndex + safePageSize, totalItems);
        return new PaginationView<>(
                items.subList(fromIndex, toIndex),
                currentPage,
                totalPages,
                safePageSize,
                totalItems,
                fromIndex + 1,
                toIndex
        );
    }

    private String resolveActiveCategory(String requestedCategory, List<String> categories) {
        String normalized = normalize(requestedCategory);
        if (!StringUtils.hasText(normalized) || "all".equalsIgnoreCase(normalized)) {
            return "all";
        }
        return categories.stream()
                .filter(category -> category.equalsIgnoreCase(normalized))
                .findFirst()
                .orElse("all");
    }

    private OrderRequest normalizeOrderRequest(OrderRequest request) {
        OrderRequest normalized = new OrderRequest();
        normalized.setCustomerName(normalize(request.getCustomerName()));
        normalized.setZipCode(postalCodeLookupService.normalize(request.getZipCode()));
        normalized.setAddress(normalize(request.getAddress()));
        normalized.setAddressDetail(normalize(request.getAddressDetail()));
        normalized.setBuildingName(normalize(request.getBuildingName()));
        normalized.setRoomNumber(normalize(request.getRoomNumber()));
        normalized.setPhone(normalizePhone(request.getPhone()));
        normalized.setDeliveryDate(normalize(request.getDeliveryDate()));
        normalized.setDeliveryTime(normalize(request.getDeliveryTime()));
        return normalized;
    }

    private record CartAddResult(boolean success, int added, int productCount, String errorCode) {
        static CartAddResult success(int added, int productCount) {
            return new CartAddResult(true, added, productCount, "");
        }

        static CartAddResult failure(String errorCode) {
            return new CartAddResult(false, 0, 0, errorCode);
        }
    }
}
