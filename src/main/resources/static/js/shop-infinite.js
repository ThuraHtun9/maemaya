// Storefront infinite scroll, skeleton loading, and category scrollspy.
//
// Pure progressive enhancement on top of the existing server-rendered,
// numbered-pagination shop page (see ShopController#index and shop.js's
// loadShopResults). No backend changes: this module fetches the exact
// same "?category=&page=&lang=&q=" fragment URLs the numbered pager
// already links to (with the X-Requested-With header that makes the
// controller return just the #shopResults fragment), then appends the
// new page's products into the page already on screen instead of
// swapping the whole fragment out.
//
// Integration points this relies on (added alongside this file):
//   - #shopResults carries data-active-category / data-search-query /
//     data-current-page / data-total-pages / data-has-more.
//   - Each .category-section carries data-category.
//   - #scrollSentinel / #loadMoreBtn / #loadMoreError+#loadMoreRetryBtn /
//     #endOfResults / #skeletonBatch / #shopLoadingStatus are static
//     scaffolding already in the fragment.
//   - shop.js dispatches a "shop:resultsReplaced" document event after
//     every outerHTML swap (category click, search, pagination-link
//     click) and exposes window.MaeMayaShop.wireCartButtons, which this
//     module reuses instead of re-implementing cart wiring.
(function () {
    "use strict";

    // Progressive enhancement only: without IntersectionObserver, do
    // nothing at all and leave the always-present numbered pagination
    // (never removed from the DOM) as the only, fully-working way to
    // page through products.
    if (!("IntersectionObserver" in window)) {
        return;
    }

    const SCROLL_STORAGE_KEY = "maeMayaShopScrollState";
    const SKELETON_DELAY_MS = 180;
    const SKELETON_CARD_COUNT = 6;
    const SENTINEL_ROOT_MARGIN = "0px 0px 800px 0px";
    const SCROLLSPY_ROOT_MARGIN = "-45% 0px -45% 0px";
    const SCROLLSPY_MIN_RATIO = 0.12;
    const SCROLL_SAVE_DEBOUNCE_MS = 220;

    const prefersReducedMotion = () =>
        !!(window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches);

    let dom = null;
    let pageState = null;
    let isLoading = false;
    let fetchController = null;
    let sentinelObserver = null;
    let scrollspyObserver = null;
    let skeletonTimer = null;
    let scrollSaveTimer = null;
    let activeSpyCategory = null;
    let verifiedWorking = false;
    let restoredOnce = false;

    function readDom() {
        const root = document.getElementById("shopResults");
        if (!root) {
            return null;
        }
        return {
            root,
            controls: document.getElementById("infiniteScrollControls"),
            sentinel: document.getElementById("scrollSentinel"),
            loadMoreBtn: document.getElementById("loadMoreBtn"),
            errorBox: document.getElementById("loadMoreError"),
            retryBtn: document.getElementById("loadMoreRetryBtn"),
            endOfResults: document.getElementById("endOfResults"),
            skeletonBatch: document.getElementById("skeletonBatch"),
            statusLive: document.getElementById("shopLoadingStatus"),
            paginationShell: root.querySelector(".pagination-shell"),
            categoryBar: root.querySelector(".shop-category-bar")
        };
    }

    function readPageState(root) {
        const ds = root.dataset;
        return {
            activeCategory: ds.activeCategory || "all",
            searchQuery: ds.searchQuery || "",
            currentPage: Number(ds.currentPage || "1"),
            totalPages: Number(ds.totalPages || "1"),
            hasMore: ds.hasMore === "true"
        };
    }

    function cssEscape(value) {
        if (window.CSS && typeof window.CSS.escape === "function") {
            return window.CSS.escape(value);
        }
        return String(value).replace(/["\\]/g, "\\$&");
    }

    function announce(text) {
        if (dom.statusLive && text) {
            dom.statusLive.textContent = text;
        }
    }

    // ---- lifecycle ----

    function init() {
        dom = readDom();
        if (!dom || !dom.root) {
            return;
        }
        pageState = readPageState(dom.root);
        isLoading = false;
        verifiedWorking = false;
        activeSpyCategory = null;

        if (dom.paginationShell) {
            dom.paginationShell.classList.remove("is-superseded");
        }
        if (dom.endOfResults) {
            dom.endOfResults.hidden = true;
        }
        hideError();
        hideSkeleton();

        setupSentinel();
        setupScrollspy();
        wireControls();
    }

    function teardownForNavigation() {
        if (fetchController) {
            fetchController.abort();
            fetchController = null;
        }
        isLoading = false;
        if (sentinelObserver) {
            sentinelObserver.disconnect();
            sentinelObserver = null;
        }
        if (scrollspyObserver) {
            scrollspyObserver.disconnect();
            scrollspyObserver = null;
        }
        cancelSkeletonTimer();
    }

    function wireControls() {
        if (dom.loadMoreBtn) {
            dom.loadMoreBtn.addEventListener("click", () => {
                loadNextPage();
            });
        }
        if (dom.retryBtn) {
            dom.retryBtn.addEventListener("click", () => {
                loadNextPage();
            });
        }
    }

    // ---- sentinel / fetching ----

    function setupSentinel() {
        if (!pageState.hasMore || !dom.sentinel) {
            return;
        }
        sentinelObserver = new IntersectionObserver((entries) => {
            entries.forEach((entry) => {
                if (entry.isIntersecting) {
                    loadNextPage();
                }
            });
        }, { rootMargin: SENTINEL_ROOT_MARGIN });
        sentinelObserver.observe(dom.sentinel);
    }

    function buildPageUrl(page) {
        const params = new URLSearchParams();
        params.set("category", pageState.activeCategory);
        params.set("page", String(page));
        params.set("lang", document.documentElement.lang || "en");
        params.set("q", pageState.searchQuery || "");
        return window.location.pathname + "?" + params.toString();
    }

    async function loadNextPage() {
        if (isLoading || !pageState.hasMore) {
            return;
        }
        isLoading = true;
        hideError();
        scheduleSkeleton();

        if (fetchController) {
            fetchController.abort();
        }
        fetchController = new AbortController();
        const nextPage = pageState.currentPage + 1;

        try {
            const response = await fetch(buildPageUrl(nextPage), {
                headers: { "X-Requested-With": "XMLHttpRequest" },
                signal: fetchController.signal
            });
            if (!response.ok) {
                throw new Error("Unexpected response status: " + response.status);
            }
            const html = await response.text();
            const parsedRoot = new DOMParser().parseFromString(html, "text/html").getElementById("shopResults");
            if (!parsedRoot) {
                throw new Error("Unexpected response shape");
            }
            appendPage(parsedRoot);
        } catch (error) {
            if (error && error.name === "AbortError") {
                // Superseded by a category/search/pagination navigation -
                // not a failure, nothing to show the user.
                return;
            }
            showError();
        } finally {
            isLoading = false;
            cancelSkeleton();
        }
    }

    // ---- merging a fetched page into the page already on screen ----

    function appendPage(newRoot) {
        const newState = readPageState(newRoot);
        const newSections = Array.from(newRoot.querySelectorAll(".category-section"));
        const newlyAddedCards = [];
        const newlyAddedHeaders = [];

        newSections.forEach((section) => {
            const categoryKey = section.dataset.category || "";
            const grid = section.querySelector(".product-grid");
            if (!grid) {
                return;
            }

            // Wire cart buttons once, while these cards are still detached
            // from the live document - wireCartButtons attaches fresh
            // listeners every time it's called, so each card must only
            // ever pass through it once no matter how many batches load.
            if (window.MaeMayaShop && window.MaeMayaShop.wireCartButtons) {
                window.MaeMayaShop.wireCartButtons(grid);
            }

            const cards = Array.from(grid.querySelectorAll(".product-card[data-product-id]"));
            const existingSection = dom.root.querySelector(
                '.category-section[data-category="' + cssEscape(categoryKey) + '"]'
            );

            if (existingSection) {
                const existingGrid = existingSection.querySelector(".product-grid");
                const existingIds = new Set(
                    Array.from(existingGrid.querySelectorAll(".product-card[data-product-id]"))
                        .map((card) => card.dataset.productId)
                );
                cards.forEach((card) => {
                    if (existingIds.has(card.dataset.productId)) {
                        return; // defensive dedupe - shouldn't happen, but never show the same product twice
                    }
                    markEntering(card);
                    existingGrid.appendChild(card);
                    newlyAddedCards.push(card);
                });
                const countEl = existingSection.querySelector(".category-count");
                const newCountEl = section.querySelector(".category-count");
                if (countEl && newCountEl) {
                    countEl.textContent = newCountEl.textContent;
                }
            } else {
                cards.forEach(markEntering);
                newlyAddedCards.push(...cards);
                if (dom.skeletonBatch) {
                    dom.skeletonBatch.insertAdjacentElement("beforebegin", section);
                }
                const header = section.querySelector(".category-header");
                if (header) {
                    newlyAddedHeaders.push(header);
                }
            }
        });

        if (newlyAddedCards.length > 0) {
            requestAnimationFrame(() => {
                requestAnimationFrame(() => {
                    newlyAddedCards.forEach((card) => card.classList.add("is-visible"));
                });
            });
        }

        if (scrollspyObserver) {
            newlyAddedHeaders.forEach((header) => scrollspyObserver.observe(header));
        }

        pageState = newState;
        dom.root.dataset.currentPage = String(newState.currentPage);
        dom.root.dataset.totalPages = String(newState.totalPages);
        dom.root.dataset.hasMore = String(newState.hasMore);

        updateHistoryPage(newState.currentPage);
        saveScrollState();

        if (!verifiedWorking) {
            verifiedWorking = true;
            // Infinite scroll has now actually loaded a batch successfully -
            // only now does the numbered pager (kept in the DOM, never
            // removed) step aside.
            if (dom.paginationShell) {
                dom.paginationShell.classList.add("is-superseded");
            }
        }

        if (newState.hasMore) {
            if (sentinelObserver && dom.sentinel) {
                sentinelObserver.observe(dom.sentinel);
            }
        } else {
            finishCatalog();
        }
    }

    function markEntering(card) {
        if (!prefersReducedMotion()) {
            card.classList.add("is-entering");
        }
    }

    function finishCatalog() {
        if (sentinelObserver) {
            sentinelObserver.disconnect();
            sentinelObserver = null;
        }
        if (dom.controls) {
            dom.controls.hidden = true;
        }
        if (dom.endOfResults) {
            dom.endOfResults.hidden = false;
            announce(dom.endOfResults.textContent);
        }
    }

    // ---- error / skeleton UI ----

    function showError() {
        if (dom.errorBox) {
            dom.errorBox.hidden = false;
            const span = dom.errorBox.querySelector("span");
            announce(span ? span.textContent : "");
        }
        // Stop auto-retrying on scroll - the retry button is the only way
        // back in until the next successful load re-observes the sentinel.
        if (sentinelObserver && dom.sentinel) {
            sentinelObserver.unobserve(dom.sentinel);
        }
    }

    function hideError() {
        if (dom.errorBox) {
            dom.errorBox.hidden = true;
        }
    }

    function scheduleSkeleton() {
        cancelSkeletonTimer();
        skeletonTimer = window.setTimeout(showSkeleton, SKELETON_DELAY_MS);
    }

    function cancelSkeleton() {
        cancelSkeletonTimer();
        hideSkeleton();
    }

    function cancelSkeletonTimer() {
        if (skeletonTimer) {
            window.clearTimeout(skeletonTimer);
            skeletonTimer = null;
        }
    }

    function showSkeleton() {
        skeletonTimer = null;
        if (!dom.skeletonBatch) {
            return;
        }
        if (dom.skeletonBatch.childElementCount === 0) {
            buildSkeletonCards(dom.skeletonBatch);
        }
        dom.skeletonBatch.hidden = false;
    }

    function hideSkeleton() {
        if (!dom.skeletonBatch) {
            return;
        }
        dom.skeletonBatch.hidden = true;
        dom.skeletonBatch.innerHTML = "";
    }

    function buildSkeletonCards(container) {
        for (let i = 0; i < SKELETON_CARD_COUNT; i += 1) {
            const card = document.createElement("div");
            card.className = "product-card is-skeleton";
            card.setAttribute("aria-hidden", "true");
            card.innerHTML =
                '<div class="product-media-wrap"><div class="skeleton-block skeleton-media"></div></div>' +
                '<div class="skeleton-block skeleton-line skeleton-line-title"></div>' +
                '<div class="skeleton-block skeleton-line skeleton-line-price"></div>';
            container.appendChild(card);
        }
    }

    // ---- scrollspy (only meaningful on the "all categories" view) ----

    function setupScrollspy() {
        activeSpyCategory = null;
        clearSpyHighlight();

        const spyEligible = pageState.activeCategory === "all" && !pageState.searchQuery;
        if (!spyEligible) {
            return;
        }

        const headers = Array.from(dom.root.querySelectorAll(".category-header"));
        if (headers.length === 0) {
            return;
        }

        scrollspyObserver = new IntersectionObserver(handleScrollspy, {
            rootMargin: SCROLLSPY_ROOT_MARGIN
        });
        headers.forEach((header) => scrollspyObserver.observe(header));
    }

    function handleScrollspy(entries) {
        let best = null;
        entries.forEach((entry) => {
            if (entry.isIntersecting && entry.intersectionRatio >= SCROLLSPY_MIN_RATIO) {
                if (!best || entry.intersectionRatio > best.intersectionRatio) {
                    best = entry;
                }
            }
        });
        if (!best) {
            return;
        }
        const section = best.target.closest(".category-section");
        const category = section && section.dataset.category;
        if (!category || category === activeSpyCategory) {
            return;
        }
        activeSpyCategory = category;
        highlightSpyCategory(category);
    }

    function findCategoryLink(category) {
        if (!dom.categoryBar) {
            return null;
        }
        const links = Array.from(dom.categoryBar.querySelectorAll(".shop-category-link"));
        return links.find((link) => {
            try {
                return new URL(link.href, window.location.origin).searchParams.get("category") === category;
            } catch (error) {
                return false;
            }
        }) || null;
    }

    function clearSpyHighlight() {
        if (dom && dom.categoryBar) {
            dom.categoryBar.querySelectorAll(".shop-category-link.is-in-view").forEach((el) => {
                el.classList.remove("is-in-view");
            });
        }
    }

    function highlightSpyCategory(category) {
        clearSpyHighlight();
        const link = findCategoryLink(category);
        if (!link) {
            return;
        }
        link.classList.add("is-in-view");
        link.scrollIntoView({
            inline: "center",
            block: "nearest",
            behavior: prefersReducedMotion() ? "auto" : "smooth"
        });
    }

    // ---- history + scroll-position persistence ----
    //
    // The app has no separate product-detail page (confirmed against
    // ShopController - there is no /product/{id} route), so the
    // realistic "navigate away and come back" flows are cart/checkout
    // and plain browser back/forward. This restores how far the user
    // had scrolled/loaded for the *same* category+search, pragmatically,
    // via sessionStorage rather than a real product-detail round trip.

    function updateHistoryPage(page) {
        try {
            const url = new URL(window.location.href);
            url.searchParams.set("page", String(page));
            window.history.replaceState(window.history.state || null, "", url.toString());
        } catch (error) {
            // History API unavailable/blocked - infinite scroll still
            // works, the URL just won't reflect how far the user scrolled.
        }
    }

    function saveScrollState() {
        try {
            sessionStorage.setItem(SCROLL_STORAGE_KEY, JSON.stringify({
                category: pageState.activeCategory,
                query: pageState.searchQuery,
                page: pageState.currentPage,
                scrollY: window.scrollY
            }));
        } catch (error) {
            // sessionStorage unavailable (private mode, quota, disabled) -
            // scroll position just won't be restored on return.
        }
    }

    function scheduleScrollSave() {
        if (scrollSaveTimer) {
            return;
        }
        scrollSaveTimer = window.setTimeout(() => {
            scrollSaveTimer = null;
            saveScrollState();
        }, SCROLL_SAVE_DEBOUNCE_MS);
    }

    function clearStaleScrollState() {
        try {
            const raw = sessionStorage.getItem(SCROLL_STORAGE_KEY);
            if (!raw) {
                return;
            }
            const saved = JSON.parse(raw);
            if (saved.category !== pageState.activeCategory || saved.query !== pageState.searchQuery) {
                sessionStorage.removeItem(SCROLL_STORAGE_KEY);
            }
        } catch (error) {
            // malformed/inaccessible storage - nothing to clean up
        }
    }

    async function attemptScrollRestore() {
        if (restoredOnce) {
            return;
        }
        restoredOnce = true;

        let saved = null;
        try {
            const raw = sessionStorage.getItem(SCROLL_STORAGE_KEY);
            saved = raw ? JSON.parse(raw) : null;
        } catch (error) {
            saved = null;
        }
        if (!saved || saved.category !== pageState.activeCategory || saved.query !== pageState.searchQuery) {
            return;
        }
        if (!(saved.page > pageState.currentPage)) {
            return;
        }

        const targetPage = saved.page;
        const targetScrollY = saved.scrollY || 0;

        while (pageState.hasMore && pageState.currentPage < targetPage) {
            const before = pageState.currentPage;
            await loadNextPage(); // eslint-disable-line no-await-in-loop
            if (pageState.currentPage === before) {
                break; // the fetch failed/aborted - stop here, let the user scroll/retry manually
            }
        }
        window.scrollTo(0, targetScrollY);
    }

    // ---- boot ----

    window.addEventListener("scroll", scheduleScrollSave, { passive: true });
    window.addEventListener("pagehide", saveScrollState);

    init();
    if (dom) {
        attemptScrollRestore();
    }

    document.addEventListener("shop:resultsReplaced", () => {
        teardownForNavigation();
        init();
        if (dom) {
            // A deliberate navigation to a different category/search result
            // set - start fresh rather than replaying an old scroll
            // position into content it doesn't belong to.
            clearStaleScrollState();
        }
    });
})();
