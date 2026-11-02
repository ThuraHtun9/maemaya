(function () {
    function getOrderCard(el) {
        return el.closest(".order-card");
    }

    function showFeedback(card, message, isError) {
        const feedbackEl = card ? card.querySelector("#orderCardFeedback") : null;
        if (!feedbackEl) {
            return;
        }
        if (!message) {
            feedbackEl.textContent = "";
            feedbackEl.className = "manage-feedback";
            return;
        }
        feedbackEl.textContent = message;
        feedbackEl.className = "manage-feedback is-visible" + (isError ? " is-error" : "");
    }

    function refreshOrderCard(card) {
        const orderNumber = card.dataset.orderNumber || "";
        const phone = card.dataset.phone || "";
        const customerName = card.dataset.customerName || "";
        const params = new URLSearchParams();
        if (orderNumber) {
            params.set("orderNumber", orderNumber);
        }
        if (phone) {
            params.set("phone", phone);
        }
        if (customerName) {
            params.set("customerName", customerName);
        }
        return fetch("/order/manage?" + params.toString(), {
            headers: { "X-Requested-With": "XMLHttpRequest" }
        }).then(function (response) {
            if (!response.ok) {
                throw new Error("order-card refresh failed");
            }
            return response.text();
        }).then(function (html) {
            if (!html || !html.trim()) {
                // Nothing came back (e.g. the order no longer matches this
                // lookup) - reload the page instead of silently blanking the card.
                window.location.reload();
                return;
            }
            card.outerHTML = html;
        });
    }

    function handleActionSubmit(form) {
        const card = getOrderCard(form);
        if (!card) {
            form.submit();
            return;
        }
        const formData = new FormData(form);

        fetch(form.action, {
            method: "POST",
            headers: { "X-Requested-With": "XMLHttpRequest" },
            body: formData
        })
            .then(function (response) {
                if (!response.ok) {
                    throw new Error("order action failed");
                }
                return response.json();
            })
            .then(function (data) {
                if (data.success) {
                    return refreshOrderCard(card);
                }
                showFeedback(card, data.message, true);
            })
            .catch(function () {
                // Network/server hiccup - fall back to the old full-page submit so the action still completes.
                form.submit();
            });
    }

    document.addEventListener("submit", function (event) {
        const form = event.target;
        if (!(form instanceof HTMLFormElement)) {
            return;
        }

        if (form.classList.contains("qty-reduce-form") || form.classList.contains("qty-increase-form")) {
            event.preventDefault();
            handleActionSubmit(form);
            return;
        }

        if (form.classList.contains("cancel-order-form")) {
            event.preventDefault();
            const confirmMessage = form.dataset.confirmMessage || "Cancel this order?";
            if (!window.confirm(confirmMessage)) {
                return;
            }
            handleActionSubmit(form);
        }
    });
})();
