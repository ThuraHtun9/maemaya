(function () {
    const menu = document.querySelector(".quick-menu");
    if (!menu) {
        return;
    }
    const summary = menu.querySelector("summary");
    if (!summary) {
        return;
    }

    const isOpen = () => menu.hasAttribute("open");
    const setExpanded = (expanded) => {
        summary.setAttribute("aria-expanded", expanded ? "true" : "false");
    };
    const closeMenu = () => {
        menu.removeAttribute("open");
        setExpanded(false);
    };
    const openMenu = () => {
        menu.setAttribute("open", "");
        setExpanded(true);
    };

    setExpanded(isOpen());

    summary.addEventListener("click", (event) => {
        event.preventDefault();
        if (isOpen()) {
            closeMenu();
        } else {
            openMenu();
        }
    });

    document.addEventListener("click", (event) => {
        if (!menu.contains(event.target)) {
            closeMenu();
        }
    });

    document.addEventListener("keydown", (event) => {
        if (event.key === "Escape") {
            closeMenu();
        }
    });

    menu.querySelectorAll("a").forEach((link) => {
        link.addEventListener("click", () => {
            closeMenu();
        });
    });

    window.addEventListener("resize", () => {
        if (window.innerWidth > 1260) {
            closeMenu();
        }
    });
})();

(function () {
    const searchInput = document.getElementById("productSearch");
    const searchForm = document.getElementById("shopSearchForm");
    const resultsWrapper = document.querySelector(".menu-section");
    const flashHost = document.getElementById("flashHost");
    const cartButton = document.querySelector(".cart-btn");
    const csrfToken = document.querySelector('meta[name="_csrf"]')?.getAttribute("content");
    const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.getAttribute("content");
    const requestFailedMessage =
        document.querySelector('meta[name="cart-request-failed-message"]')?.getAttribute("content") ||
        "Cart update failed. Please try again.";

    const showFlash = (isSuccess, message) => {
        if (!flashHost || !message) {
            return;
        }
        const flash = document.createElement("div");
        flash.className = `flash ${isSuccess ? "flash-success" : "flash-error"}`;
        flash.textContent = message;
        flashHost.prepend(flash);
        window.setTimeout(() => flash.remove(), 2800);
    };

    const setCartCount = (count) => {
        if (!cartButton || !Number.isFinite(count)) {
            return;
        }
        let badge = cartButton.querySelector(".cart-badge");
        if (count <= 0) {
            if (badge) {
                badge.remove();
            }
            return;
        }
        if (!badge) {
            badge = document.createElement("span");
            badge.className = "cart-badge";
            cartButton.appendChild(badge);
        }
        badge.textContent = String(count);
    };

    const postCart = async (url, productId, quantity) => {
        const headers = {
            "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8"
        };
        if (csrfToken && csrfHeader) {
            headers[csrfHeader] = csrfToken;
        }
        const response = await fetch(url, {
            method: "POST",
            headers,
            body: new URLSearchParams({
                productId: String(productId),
                quantity: String(quantity)
            }).toString()
        });
        if (!response.ok) {
            return null;
        }
        return response.json();
    };

    // Add/+/− cart button wiring. Callable again after every AJAX fragment swap
    // so freshly-inserted product cards keep working.
    function wireCartButtons(root) {
        const scope = root || document;
        const cards = Array.from(scope.querySelectorAll(".product-card[data-product-id]"));
        if (cards.length === 0) {
            return;
        }

        cards.forEach((card) => {
            const productId = Number(card.dataset.productId);
            const stock = Number(card.dataset.stock || "0");
            const addButton = card.querySelector(".add-cart-btn");
            const qtyControl = card.querySelector(".qty-control");
            const minusButton = card.querySelector(".qty-minus");
            const plusButton = card.querySelector(".qty-plus");
            const qtyValue = card.querySelector(".qty-value");

            let inCart = Number(card.dataset.inCart || "0");
            if (!Number.isFinite(inCart) || inCart < 0) {
                inCart = 0;
            }

            const renderQuantityState = () => {
                const hasItem = inCart > 0;
                card.dataset.inCart = String(inCart);
                if (qtyValue) {
                    qtyValue.textContent = String(inCart);
                }
                if (addButton) {
                    addButton.classList.toggle("is-hidden", hasItem);
                    addButton.disabled = false;
                }
                if (qtyControl) {
                    qtyControl.classList.toggle("is-visible", hasItem);
                }
                if (plusButton) {
                    plusButton.disabled = inCart >= stock;
                }
                if (minusButton) {
                    minusButton.disabled = inCart <= 0;
                }
            };

            renderQuantityState();

            if (addButton) {
                addButton.addEventListener("click", async (event) => {
                    event.preventDefault();
                    event.stopPropagation();
                    addButton.disabled = true;

                    const result = await postCart("/cart/add", productId, 1);
                    if (!result) {
                        addButton.disabled = false;
                        showFlash(false, requestFailedMessage);
                        return;
                    }

                    if (!result.success) {
                        addButton.disabled = false;
                        showFlash(false, result.message || requestFailedMessage);
                        setCartCount(Number(result.cartCount || 0));
                        inCart = Number(result.productCount || 0);
                        renderQuantityState();
                        return;
                    }

                    inCart = Number(result.productCount || inCart + 1);
                    setCartCount(Number(result.cartCount || 0));
                    renderQuantityState();
                    addButton.classList.remove("success-pop");
                    void addButton.offsetWidth;
                    addButton.classList.add("success-pop");
                    showFlash(true, result.message || "");
                });
            }

            if (plusButton) {
                plusButton.addEventListener("click", async (event) => {
                    event.preventDefault();
                    event.stopPropagation();
                    if (inCart >= stock) {
                        return;
                    }
                    plusButton.disabled = true;

                    const result = await postCart("/cart/add", productId, 1);
                    if (!result) {
                        plusButton.disabled = false;
                        showFlash(false, requestFailedMessage);
                        return;
                    }

                    if (!result.success) {
                        plusButton.disabled = false;
                        showFlash(false, result.message || requestFailedMessage);
                        setCartCount(Number(result.cartCount || 0));
                        inCart = Number(result.productCount || inCart);
                        renderQuantityState();
                        return;
                    }

                    inCart = Number(result.productCount || (inCart + 1));
                    setCartCount(Number(result.cartCount || 0));
                    renderQuantityState();
                });
            }

            if (minusButton) {
                minusButton.addEventListener("click", async (event) => {
                    event.preventDefault();
                    event.stopPropagation();
                    if (inCart <= 0) {
                        return;
                    }
                    minusButton.disabled = true;

                    const result = await postCart("/cart/remove", productId, 1);
                    if (!result) {
                        minusButton.disabled = false;
                        showFlash(false, requestFailedMessage);
                        return;
                    }

                    if (!result.success) {
                        minusButton.disabled = false;
                        showFlash(false, result.message || requestFailedMessage);
                        setCartCount(Number(result.cartCount || 0));
                        inCart = Number(result.productCount || inCart);
                        renderQuantityState();
                        return;
                    }

                    inCart = Number(result.productCount || Math.max(0, inCart - 1));
                    setCartCount(Number(result.cartCount || 0));
                    renderQuantityState();
                });
            }
        });
    }

    // Shows a right-edge fade on the category bar while there are more
    // categories scrolled off-screen; re-invoked after every AJAX swap
    // since the bar itself gets replaced along with the rest of the fragment.
    function wireCategoryBarFade(root) {
        const scope = root || document;
        const bar = scope.querySelector(".shop-category-bar");
        if (!bar) {
            return;
        }
        const update = () => {
            const hasMore = bar.scrollWidth - bar.clientWidth - bar.scrollLeft > 4;
            bar.classList.toggle("has-more-right", hasMore);
        };
        bar.addEventListener("scroll", update, { passive: true });
        window.addEventListener("resize", update, { passive: true });
        update();
    }

    // ---- AJAX shop results navigation (search / category / pagination) ----
    let activeAbortController = null;

    function syncSearchFormState(url) {
        if (!searchForm) {
            return;
        }
        try {
            const parsed = new URL(url, window.location.origin);
            const categoryInput = searchForm.querySelector('input[name="category"]');
            if (categoryInput) {
                categoryInput.value = parsed.searchParams.get("category") || "all";
            }
        } catch (error) {
            // malformed url - nothing to sync
        }
    }

    async function loadShopResults(url, options) {
        const pushState = !options || options.pushState !== false;

        if (!resultsWrapper || !document.getElementById("shopResults")) {
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
        const currentResults = document.getElementById("shopResults");
        if (!currentResults) {
            window.location.href = url;
            return;
        }
        currentResults.outerHTML = html;

        wireCartButtons(document.getElementById("shopResults"));
        wireCategoryBarFade(document.getElementById("shopResults"));
        syncSearchFormState(url);

        if (pushState) {
            history.pushState({ shopUrl: url }, "", url);
        }
    }

    // Search input: debounce, then fetch results via AJAX instead of a full reload.
    if (searchInput && searchForm) {
        // reload လုပ်ပြီးချိန်မှာ cursor ကို search box ထဲမှာပြန်ထားပေးဖို့
        if (searchInput.value) {
            searchInput.focus();
            const end = searchInput.value.length;
            searchInput.setSelectionRange(end, end);
        }

        let debounceTimer = null;
        const DEBOUNCE_DELAY = 900; // milliseconds

        searchInput.addEventListener("input", () => {
            if (debounceTimer) {
                clearTimeout(debounceTimer);
            }
            debounceTimer = setTimeout(() => {
                const categoryInput = searchForm.querySelector('input[name="category"]');
                const langInput = searchForm.querySelector('input[name="lang"]');
                const params = new URLSearchParams();
                params.set("category", categoryInput ? categoryInput.value : "all");
                params.set("page", "1");
                if (langInput && langInput.value) {
                    params.set("lang", langInput.value);
                }
                params.set("q", searchInput.value.trim());
                loadShopResults(`${searchForm.action}?${params.toString()}`);
            }, DEBOUNCE_DELAY);
        });
    }

    // Category / pagination links: intercept clicks via delegation so the
    // binding survives every AJAX fragment swap (the links themselves get
    // replaced, but this listener stays on the stable .menu-section wrapper).
    if (resultsWrapper) {
        resultsWrapper.addEventListener("click", (event) => {
            const link = event.target.closest(".shop-category-link, .pagination-link, .pagination-nav-link");
            if (!link) {
                return;
            }
            if (link.classList.contains("is-disabled")) {
                event.preventDefault();
                return;
            }
            event.preventDefault();
            loadShopResults(link.href);
        });
    }

    // Back/forward button support.
    window.addEventListener("popstate", () => {
        loadShopResults(window.location.href, { pushState: false });
    });

    wireCartButtons(document);
    wireCategoryBarFade(document);
})();

(function () {
    const prefersReduced = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    const hoverCapable = window.matchMedia && window.matchMedia("(hover: hover) and (pointer: fine)").matches;
    if (prefersReduced) {
        return;
    }

    const live3dTargets = Array.from(document.querySelectorAll("[data-live-3d]"));
    if (live3dTargets.length === 0) {
        return;
    }

    if (!hoverCapable) {
        const pulseOnce = (element) => {
            element.classList.add("is-live");
            element.style.transform = "perspective(920px) rotateX(4deg) rotateY(-8deg) translateZ(0)";
            window.setTimeout(() => {
                element.style.transform = "perspective(920px) rotateX(-3deg) rotateY(7deg) translateZ(0)";
            }, 360);
            window.setTimeout(() => {
                element.style.transform = "perspective(920px) rotateX(0deg) rotateY(0deg) translateZ(0)";
                element.classList.remove("is-live");
            }, 760);
        };

        live3dTargets.forEach((element) => {
            pulseOnce(element);
            let intervalId = window.setInterval(() => pulseOnce(element), 2800);
            element.addEventListener("pointerdown", () => pulseOnce(element), { passive: true });
            document.addEventListener("visibilitychange", () => {
                if (document.hidden) {
                    window.clearInterval(intervalId);
                    intervalId = 0;
                } else if (!intervalId) {
                    intervalId = window.setInterval(() => pulseOnce(element), 2800);
                    pulseOnce(element);
                }
            });
        });
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
