function confirmDelete(button) {
    const productName = button.getAttribute("data-product-name") || "item";
    const template = button.getAttribute("data-confirm-template") || 'Delete "{0}"?';
    return confirm(template.replace("{0}", productName));
}

function confirmCategoryDelete(button) {
    const categoryName = button.getAttribute("data-category-name") || "category";
    const template = button.getAttribute("data-confirm-template") || 'Delete "{0}" category?';
    return confirm(template.replace("{0}", categoryName));
}

function confirmOrderDelete(button) {
    const orderNumber = button.getAttribute("data-order-number") || "order";
    const template = button.getAttribute("data-confirm-template") || 'Delete order "{0}"?';
    return confirm(template.replace("{0}", orderNumber));
}

let activeAddItemForm = null;

function openAddItemModal(button) {
    activeAddItemForm = button.closest("form");
    const searchInput = document.getElementById("addItemSearch");
    searchInput.value = "";
    renderAddItemList("");
    clearAddItemFeedback();
    document.getElementById("addItemModalBackdrop").classList.add("open");
    document.getElementById("addItemModal").classList.add("open");
    document.body.classList.add("modal-open");
    // preventScroll stops the browser from scrolling the page to "reveal" this
    // input when focusing it - without it, some mobile browsers scroll the
    // whole page to the top even though the modal is already fixed/centered.
    searchInput.focus({ preventScroll: true });
}

function clearAddItemFeedback() {
    const feedbackEl = document.getElementById("addItemFeedback");
    if (feedbackEl) {
        feedbackEl.textContent = "";
        feedbackEl.className = "modal-feedback";
    }
}

function showAddItemFeedback(message, isSuccess) {
    const feedbackEl = document.getElementById("addItemFeedback");
    if (!feedbackEl) {
        return;
    }
    feedbackEl.textContent = message || "";
    feedbackEl.className = "modal-feedback is-visible " + (isSuccess ? "is-success" : "is-error");
}

let adminToastTimer = null;

// Small, non-intrusive status message (e.g. "out of stock") for quick
// inline actions like the order-item qty stepper, where there is no
// modal/feedback element already on screen to report into.
function showAdminToast(message, isError) {
    if (!message) {
        return;
    }
    let toastEl = document.getElementById("adminToast");
    if (!toastEl) {
        toastEl = document.createElement("div");
        toastEl.id = "adminToast";
        toastEl.className = "admin-toast";
        document.body.appendChild(toastEl);
    }
    toastEl.textContent = message;
    toastEl.className = "admin-toast is-visible " + (isError ? "is-error" : "is-success");
    if (adminToastTimer) {
        clearTimeout(adminToastTimer);
    }
    adminToastTimer = window.setTimeout(() => {
        toastEl.classList.remove("is-visible");
    }, 3200);
}

function closeAddItemModal() {
    document.getElementById("addItemModalBackdrop").classList.remove("open");
    document.getElementById("addItemModal").classList.remove("open");
    document.body.classList.remove("modal-open");
    activeAddItemForm = null;
}

// Product catalogs here run past 600 items; rendering them all into the DOM
// at once (especially on every keystroke while filtering) is slow enough on
// phones to feel like the page froze. Cap how many list rows we ever build,
// and don't render anything until the admin actually starts typing.
const ADD_ITEM_MAX_RESULTS = 50;

function renderAddItemList(query) {
    const master = document.getElementById("productOptionsMaster");
    const listEl = document.getElementById("addItemList");
    const emptyEl = document.getElementById("addItemEmpty");
    listEl.innerHTML = "";
    const q = query.trim().toLowerCase();

    if (q === "") {
        emptyEl.textContent = emptyEl.getAttribute("data-type-to-search") || "";
        emptyEl.style.display = "block";
        return;
    }

    const sourceItems = master.querySelectorAll("li");
    let shown = 0;
    for (let i = 0; i < sourceItems.length && shown < ADD_ITEM_MAX_RESULTS; i++) {
        const sourceLi = sourceItems[i];
        const text = sourceLi.getAttribute("data-text");
        if (!text.toLowerCase().includes(q)) {
            continue;
        }
        const item = document.createElement("li");
        item.className = "modal-product-item";
        item.textContent = text;
        item.onclick = function() {
            selectAddItemProduct(sourceLi.getAttribute("data-id"));
        };
        listEl.appendChild(item);
        shown++;
    }

    if (shown === 0) {
        emptyEl.textContent = emptyEl.getAttribute("data-no-match") || "";
        emptyEl.style.display = "block";
    } else {
        emptyEl.style.display = "none";
    }
}

function selectAddItemProduct(productId) {
    if (!activeAddItemForm) {
        return;
    }
    const hiddenInput = activeAddItemForm.querySelector("input[name='productId']");
    if (hiddenInput) {
        hiddenInput.value = productId;
    }
    const form = activeAddItemForm;
    const formData = new FormData(form);

    fetch(form.action, {
        method: "POST",
        headers: { "X-Requested-With": "XMLHttpRequest" },
        body: formData
    })
        .then(function(response) {
            if (!response.ok) {
                throw new Error("add-item request failed");
            }
            return response.json();
        })
        .then(function(data) {
            showAddItemFeedback(data.message, data.success);
            if (data.success && typeof window.loadAdminResults === "function") {
                window.loadAdminResults(window.location.href, { pushState: false });
            }
        })
        .catch(function() {
            // Network/server hiccup - fall back to the old full-page submit so the action still completes.
            closeAddItemModal();
            if (form.requestSubmit) {
                form.requestSubmit();
            } else {
                form.submit();
            }
        });
}

document.addEventListener("keydown", function(event) {
    if (event.key === "Escape") {
        closeAddItemModal();
    }
});

(function() {
    const normalize = (value) => (value || "").toString().toLowerCase().trim();

    // Quick-filter toolbars (order/request tables). Re-invoked after every AJAX
    // tab/pagination swap so freshly-inserted rows keep filtering correctly.
    function wireAdminToolbarFilters(root) {
        const scope = root || document;

        const bindToolbarFilter = (config) => {
            const input = scope.querySelector(`[data-filter-input="${config.key}"]`);
            const statusSelect = scope.querySelector(`[data-filter-status="${config.key}"]`) ||
                scope.querySelector(`[data-filter-stock="${config.key}"]`);
            const resetBtn = scope.querySelector(`[data-filter-reset="${config.key}"]`);
            const result = scope.querySelector(`[data-filter-result="${config.key}"]`);
            const rows = Array.from(scope.querySelectorAll(config.rowSelector));

            if (rows.length === 0 || (!input && !statusSelect)) {
                if (result) {
                    result.textContent = "0 / 0 shown";
                }
                return;
            }

            const apply = () => {
                const keyword = normalize(input ? input.value : "");
                const status = normalize(statusSelect ? statusSelect.value : "all");
                let visible = 0;

                rows.forEach((row) => {
                    const haystack = normalize(config.pickSearchText(row));
                    const keywordMatch = keyword.length === 0 || haystack.includes(keyword);
                    const statusMatch = config.matchStatus(row, status);
                    const show = keywordMatch && statusMatch;
                    row.hidden = !show;
                    if (show) {
                        visible += 1;
                    }
                });

                if (result) {
                    result.textContent = `${visible} / ${rows.length} shown`;
                }
            };

            if (input) {
                input.addEventListener("input", apply, { passive: true });
            }
            if (statusSelect) {
                statusSelect.addEventListener("change", apply);
            }
            if (resetBtn) {
                resetBtn.addEventListener("click", () => {
                    if (input) {
                        input.value = "";
                    }
                    if (statusSelect) {
                        statusSelect.selectedIndex = 0;
                    }
                    apply();
                });
            }

            apply();
        };

        bindToolbarFilter({
            key: "orders",
            rowSelector: "tr.order-row",
            pickSearchText: (row) => `${row.dataset.orderNumber || ""} ${row.dataset.orderCustomer || ""} ${row.dataset.orderPhone || ""}`,
            matchStatus: (row, status) => {
                if (status === "all") {
                    return true;
                }
                return normalize(row.dataset.orderStatus) === status;
            }
        });

        bindToolbarFilter({
            key: "requests",
            rowSelector: "tr.request-row",
            pickSearchText: (row) => `${row.dataset.requestRequester || ""} ${row.dataset.requestContact || ""} ${row.dataset.requestProduct || ""}`,
            matchStatus: (row, status) => {
                if (status === "all") {
                    return true;
                }
                return normalize(row.dataset.requestStatus) === status;
            }
        });
    }

    // ---- AJAX admin tab navigation (tab switch / pagination / order search) ----
    const adminContainer = document.querySelector(".admin-container");
    const langPickerForm = document.querySelector(".lang-picker-form");
    let activeAbortController = null;

    function syncAdminFormState(url) {
        if (!langPickerForm) {
            return;
        }
        try {
            const parsed = new URL(url, window.location.origin);
            langPickerForm.querySelectorAll("input[type=\"hidden\"]").forEach((input) => {
                if (parsed.searchParams.has(input.name)) {
                    input.value = parsed.searchParams.get(input.name);
                }
            });
        } catch (error) {
            // malformed url - nothing to sync
        }
    }

    async function loadAdminResults(url, options) {
        const pushState = !options || options.pushState !== false;

        if (!adminContainer || !document.getElementById("adminTabResults")) {
            window.location.href = url;
            return;
        }

        if (activeAbortController) {
            activeAbortController.abort();
        }
        const controller = new AbortController();
        activeAbortController = controller;

        let response;
        try {
            response = await fetch(url, {
                headers: { "X-Requested-With": "XMLHttpRequest" },
                signal: controller.signal
            });
        } catch (error) {
            if (error.name === "AbortError") {
                return;
            }
            window.location.href = url;
            return;
        }

        if (!response.ok) {
            window.location.href = url;
            return;
        }

        const html = await response.text();
        const currentResults = document.getElementById("adminTabResults");
        if (!currentResults) {
            window.location.href = url;
            return;
        }
        currentResults.outerHTML = html;

        wireAdminToolbarFilters(document);
        syncAdminFormState(url);

        if (pushState) {
            history.pushState({ adminUrl: url }, "", url);
        }
    }

    if (adminContainer) {
        adminContainer.addEventListener("click", (event) => {
            const link = event.target.closest(".admin-tab-btn, .pagination-link, .search-clear");
            if (!link) {
                return;
            }
            if (link.classList.contains("is-disabled")) {
                event.preventDefault();
                return;
            }
            event.preventDefault();
            loadAdminResults(link.href);
        });

        adminContainer.addEventListener("submit", (event) => {
            const searchForm = event.target.closest(".order-search-form");
            if (searchForm) {
                event.preventDefault();
                const params = new URLSearchParams(new FormData(searchForm));
                loadAdminResults(`${searchForm.action}?${params.toString()}`);
                return;
            }

            const qtyForm = event.target.closest(".order-item-reduce-form, .order-item-increase-form");
            if (qtyForm) {
                event.preventDefault();
                const qtyButton = qtyForm.querySelector("button[type=submit]");
                // Guard against a double-tap firing a second request before the
                // first one's table refresh has a chance to replace this button.
                if (qtyButton && qtyButton.disabled) {
                    return;
                }
                if (qtyButton) {
                    qtyButton.disabled = true;
                }
                const formData = new FormData(qtyForm);
                fetch(qtyForm.action, {
                    method: "POST",
                    headers: { "X-Requested-With": "XMLHttpRequest" },
                    body: formData
                })
                    .then((response) => {
                        if (!response.ok) {
                            throw new Error("order item qty request failed");
                        }
                        return response.json();
                    })
                    .then((data) => {
                        if (!data.success) {
                            showAdminToast(data.message, true);
                        }
                        // Refresh the table in place (preserves the expanded card,
                        // see loadAdminResults) instead of a full page reload. This
                        // also re-enables the button via a fresh (non-disabled) one.
                        loadAdminResults(window.location.href, { pushState: false });
                    })
                    .catch(() => {
                        // Network/server hiccup - fall back to the old full-page submit.
                        if (qtyButton) {
                            qtyButton.disabled = false;
                        }
                        if (qtyForm.requestSubmit) {
                            qtyForm.requestSubmit();
                        } else {
                            qtyForm.submit();
                        }
                    });
            }
        });
    }

    window.addEventListener("popstate", () => {
        loadAdminResults(window.location.href, { pushState: false });
    });

    wireAdminToolbarFilters(document);

    // Exposed so the Add Item modal (outside this closure) can refresh the
    // orders table in place after an AJAX add, without a full page reload.
    window.loadAdminResults = loadAdminResults;
})();

(function() {
    const prefersReduced = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    const hoverCapable = window.matchMedia && window.matchMedia("(hover: hover) and (pointer: fine)").matches;
    if (prefersReduced || !hoverCapable) {
        return;
    }

    const live3dTargets = Array.from(document.querySelectorAll("[data-live-3d]"));
    if (live3dTargets.length === 0) {
        return;
    }

    const maxTilt = 10;
    const reset = (element) => {
        element.style.transform = "perspective(920px) rotateX(0deg) rotateY(0deg) translateZ(0)";
        element.classList.remove("is-live");
    };

    live3dTargets.forEach((element) => {
        let rafId = 0;
        let hasPointer = false;
        let pointerX = 0;
        let pointerY = 0;

        const render = () => {
            rafId = 0;
            if (!hasPointer) {
                return;
            }
            const rect = element.getBoundingClientRect();
            const px = (pointerX - rect.left) / Math.max(rect.width, 1);
            const py = (pointerY - rect.top) / Math.max(rect.height, 1);
            const tiltY = (px - 0.5) * maxTilt * 2;
            const tiltX = (0.5 - py) * maxTilt * 1.8;
            element.style.transform =
                `perspective(920px) rotateX(${tiltX.toFixed(2)}deg) rotateY(${tiltY.toFixed(2)}deg) translateZ(0)`;
            element.classList.add("is-live");
        };

        const resetAndStop = () => {
            hasPointer = false;
            if (rafId) {
                window.cancelAnimationFrame(rafId);
                rafId = 0;
            }
            reset(element);
        };

        reset(element);
        element.addEventListener("pointermove", (event) => {
            pointerX = event.clientX;
            pointerY = event.clientY;
            hasPointer = true;
            if (!rafId) {
                rafId = window.requestAnimationFrame(render);
            }
        }, { passive: true });
        element.addEventListener("pointerleave", resetAndStop);
        element.addEventListener("pointercancel", resetAndStop);
    });
})();
