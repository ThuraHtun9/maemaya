document.addEventListener("DOMContentLoaded", function () {
    const wrapper = document.querySelector(".cart-container");
    if (!wrapper) {
        return;
    }

    let busy = false;

    function getCsrfHeaders() {
        const csrfToken = document.querySelector('meta[name="_csrf"]')?.getAttribute("content");
        const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.getAttribute("content");
        const headers = { "Content-Type": "application/x-www-form-urlencoded" };
        if (csrfToken && csrfHeader) {
            headers[csrfHeader] = csrfToken;
        }
        return headers;
    }

    function refreshCart() {
        return fetch(window.location.pathname, {
            headers: { "X-Requested-With": "XMLHttpRequest" }
        })
            .then(function (response) { return response.text(); })
            .then(function (html) {
                const parser = new DOMParser();
                const doc = parser.parseFromString(html, "text/html");
                const newContent = doc.getElementById("cartContent");
                const oldContent = document.getElementById("cartContent");
                if (newContent && oldContent) {
                    oldContent.replaceWith(newContent);
                }
            });
    }

    function changeQuantity(productId, action) {
        if (busy) {
            return;
        }
        busy = true;
        const url = action === "increase" ? "/cart/add" : "/cart/remove";
        const body = new URLSearchParams();
        body.set("productId", productId);
        body.set("quantity", "1");

        fetch(url, {
            method: "POST",
            headers: getCsrfHeaders(),
            body: body.toString()
        })
            .then(refreshCart)
            .finally(function () {
                busy = false;
            });
    }

    function clearCart() {
        if (busy) {
            return;
        }
        const confirmMessage =
            document.querySelector('meta[name="cart-clear-confirm-message"]')?.getAttribute("content") ||
            "Clear your entire cart?";
        if (!window.confirm(confirmMessage)) {
            return;
        }
        busy = true;
        fetch("/cart/clear", {
            method: "POST",
            headers: getCsrfHeaders(),
            body: ""
        })
            .then(refreshCart)
            .finally(function () {
                busy = false;
            });
    }

    wrapper.addEventListener("click", function (event) {
        const qtyBtn = event.target.closest(".qty-btn");
        if (qtyBtn) {
            const stepper = qtyBtn.closest(".qty-stepper");
            if (stepper) {
                const productId = stepper.getAttribute("data-product-id");
                const action = qtyBtn.classList.contains("qty-btn-plus") ? "increase" : "decrease";
                changeQuantity(productId, action);
            }
            return;
        }

        const clearBtn = event.target.closest("#clearCartBtn");
        if (clearBtn) {
            clearCart();
        }
    });
});