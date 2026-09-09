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

    (function () {
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
                const form = event.target.closest(".order-search-form");
                if (!form) {
                    return;
                }
                event.preventDefault();
                const params = new URLSearchParams(new FormData(form));
                loadAdminResults(`${form.action}?${params.toString()}`);
            });
        }

        window.addEventListener("popstate", () => {
            loadAdminResults(window.location.href, { pushState: false });
        });

        wireAdminToolbarFilters(document);
    })();

    (function () {
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
