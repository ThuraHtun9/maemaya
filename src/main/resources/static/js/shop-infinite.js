// Storefront infinite scroll, skeleton loading, category scrollspy and
// browsing-position restoration.
//
// Pure progressive enhancement on top of the existing server-rendered,
// numbered-pagination shop page (see ShopController#index and shop.js's
// loadShopResults). No new endpoints: this module fetches the exact same
// "?category=&page=&lang=&q=" fragment URLs the numbered pager links to
// (with the X-Requested-With header that makes the controller return just
// the #shopResults fragment), then appends the new page's products into
// the page already on screen instead of swapping the whole fragment out.
//
// Integration points this relies on:
//   - #shopResults carries data-active-category / data-search-query /
//     data-current-page / data-total-pages / data-has-more.
//   - Each .category-section carries data-category.
//   - #skeletonBatch sits directly after the last .category-section and is
//     the insertion point for appended sections; #scrollSentinel /
//     #loadMoreError+#loadMoreRetryBtn / #endOfResults / #shopLoadingStatus
//     come after it.
//   - shop.js dispatches "shop:resultsReplaced" ({ url, restore }) after
//     every outerHTML swap (category click, search, back/forward) and
//     exposes window.MaeMayaShop.wireCartButtons, reused here for cart
//     wiring instead of re-implementing it.
(function () {
    "use strict";

    // Progressive enhancement only: without IntersectionObserver, do
    // nothing at all and leave the always-present numbered pagination
    // (never removed from the DOM) as the only way to page through products.
    if (!("IntersectionObserver" in window)) {
        return;
    }

    const HISTORY_STATE_KEY = "maeMayaShop";
    const SKELETON_DELAY_MS = 180;
    const SKELETON_CARD_COUNT = 6;
    // Prefetch: start the next request while the end of the loaded content
    // is still ~800px below the viewport.
    const SENTINEL_ROOT_MARGIN = "0px 0px 800px 0px";
    const SCROLL_SAVE_DEBOUNCE_MS = 300;
    // Restore fetches run in parallel; 6 = the per-host connection limit,
    // so a typical return (a handful of pages) completes in one round trip.
    const RESTORE_CONCURRENCY = 6;
    const RESTORE_MAX_EXTRA_PAGES = 40;
    // Scrollspy "reading line": just under the sticky category bar. The
    // observed band runs from there to 20% of the viewport height.
    const SPY_LINE_GAP_PX = 8;
    const SPY_BAND_BOTTOM = "-80%";

    const prefersReducedMotion = () =>
        !!(window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches);

    let dom = null;
    let pageState = null;
    let loadedIds = new Set();
    let isLoading = false;
    let fetchController = null;
    // Bumped on every result-set change (category / search / back-forward
    // swap). Any response that started under an older generation is
    // dropped, even if it finished after the abort() call.
    let generation = 0;
    let sentinelObserver = null;
    let skeletonTimer = null;
    let scrollSaveTimer = null;

    let spyObserver = null;
    let spyVisible = new Set();
    let spyActive = null;
    let spyLine = 0;
    let spyLinks = new Map();

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
        if (dom && dom.statusLive && text) {
            dom.statusLive.textContent = text;
        }
    }

    function setPaginationSuperseded(superseded) {
        if (dom && dom.paginationShell) {
            dom.paginationShell.classList.toggle("is-superseded", superseded);
        }
    }

    // ---- lifecycle ----

    function init() {
        dom = readDom();
        if (!dom) {
            return;
        }
        pageState = readPageState(dom.root);
        isLoading = false;
        loadedIds = new Set(
            Array.from(dom.root.querySelectorAll(".product-card[data-product-id]"))
                .map((card) => card.dataset.productId)
        );

        if (dom.endOfResults) {
            dom.endOfResults.hidden = true;
        }
        hideError();
        hideSkeleton();

        // Infinite scroll owns paging from here on, so the numbered pager
        // steps aside right away instead of showing next to the automatic
        // loading. It stays in the DOM and comes back if a load fails.
        setPaginationSuperseded(pageState.hasMore);

        setupSentinel();
        setupScrollspy();
        wireControls();
    }

    function teardownForNavigation() {
        generation += 1;
        if (fetchController) {
            fetchController.abort();
            fetchController = null;
        }
        isLoading = false;
        if (sentinelObserver) {
            sentinelObserver.disconnect();
            sentinelObserver = null;
        }
        if (spyObserver) {
            spyObserver.disconnect();
            spyObserver = null;
        }
        cancelSkeleton();
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
        if (!dom.sentinel) {
            return;
        }
        sentinelObserver = new IntersectionObserver((entries) => {
            if (entries.some((entry) => entry.isIntersecting)) {
                loadNextPage();
            }
        }, { rootMargin: SENTINEL_ROOT_MARGIN });
        if (pageState.hasMore) {
            sentinelObserver.observe(dom.sentinel);
        }
    }

    // observe() always delivers a fresh initial entry, so re-observing after
    // each batch keeps loading when the sentinel is *still* within the
    // prefetch margin (tall screens, short batches). Without this the
    // observer never fired again and loading silently stopped.
    function rearmSentinel() {
        if (!sentinelObserver || !dom.sentinel) {
            return;
        }
        sentinelObserver.unobserve(dom.sentinel);
        if (pageState.hasMore) {
            sentinelObserver.observe(dom.sentinel);
        }
    }

    function buildPageUrl(page) {
        const params = new URLSearchParams();
        params.set("category", pageState.activeCategory);
        params.set("page", String(page));
        params.set("lang", document.documentElement.lang || "en");
        params.set("q", pageState.searchQuery || "");
        return window.location.pathname + "?" + params.toString();
    }

    async function fetchPage(page, signal) {
        const response = await fetch(buildPageUrl(page), {
            headers: { "X-Requested-With": "XMLHttpRequest" },
            signal
        });
        if (!response.ok) {
            throw new Error("Unexpected response status: " + response.status);
        }
        const html = await response.text();
        const parsedRoot = new DOMParser().parseFromString(html, "text/html").getElementById("shopResults");
        if (!parsedRoot) {
            throw new Error("Unexpected response shape");
        }
        return parsedRoot;
    }

    async function loadNextPage() {
        if (isLoading || !pageState || !pageState.hasMore) {
            return false;
        }
        const requestGeneration = generation;
        isLoading = true;
        hideError();
        scheduleSkeleton();

        const controller = new AbortController();
        fetchController = controller;

        try {
            const parsedRoot = await fetchPage(pageState.currentPage + 1, controller.signal);
            if (requestGeneration !== generation) {
                return false; // result set changed while this was in flight
            }
            appendPage(parsedRoot, { animate: true });
            rearmSentinel();
            return true;
        } catch (error) {
            if (requestGeneration !== generation || (error && error.name === "AbortError")) {
                return false; // superseded by a navigation - not a failure
            }
            showError();
            return false;
        } finally {
            if (requestGeneration === generation) {
                isLoading = false;
                fetchController = null;
                cancelSkeleton();
            }
        }
    }

    // ---- merging a fetched page into the page already on screen ----

    function appendPage(newRoot, options) {
        const animate = !options || options.animate !== false;
        const newState = readPageState(newRoot);
        if (newState.activeCategory !== pageState.activeCategory || newState.searchQuery !== pageState.searchQuery) {
            return; // response for a different result set - never mix it in
        }
        const newSections = Array.from(newRoot.querySelectorAll(".category-section"));
        const newlyAddedCards = [];
        const insertionPoint = dom.skeletonBatch || dom.paginationShell || dom.controls;

        newSections.forEach((section) => {
            const categoryKey = section.dataset.category || "";
            const grid = section.querySelector(".product-grid");
            if (!grid) {
                return;
            }

            // Never show the same product twice (e.g. the catalog changed
            // between two page requests and shifted an item across pages).
            Array.from(grid.querySelectorAll(".product-card[data-product-id]")).forEach((card) => {
                if (loadedIds.has(card.dataset.productId)) {
                    card.remove();
                }
            });
            const cards = Array.from(grid.querySelectorAll(".product-card[data-product-id]"));
            if (cards.length === 0) {
                return;
            }

            // Wire cart buttons exactly once per card, while still detached -
            // wireCartButtons attaches fresh listeners on every call. Each
            // card's quantity comes from the server-rendered data-in-cart
            // of this response, i.e. the cart as it is right now.
            if (window.MaeMayaShop && window.MaeMayaShop.wireCartButtons) {
                window.MaeMayaShop.wireCartButtons(grid);
            }
            cards.forEach((card) => {
                loadedIds.add(card.dataset.productId);
                if (animate) {
                    markEntering(card);
                }
            });
            newlyAddedCards.push(...cards);

            const existingSection = dom.root.querySelector(
                '.category-section[data-category="' + cssEscape(categoryKey) + '"]'
            );
            if (existingSection) {
                const existingGrid = existingSection.querySelector(".product-grid");
                cards.forEach((card) => existingGrid.appendChild(card));
                const countEl = existingSection.querySelector(".category-count");
                const newCountEl = section.querySelector(".category-count");
                if (countEl && newCountEl) {
                    countEl.textContent = newCountEl.textContent;
                }
            } else if (insertionPoint) {
                insertionPoint.insertAdjacentElement("beforebegin", section);
                if (spyObserver) {
                    spyObserver.observe(section);
                }
            }
        });

        if (animate && newlyAddedCards.length > 0) {
            requestAnimationFrame(() => {
                requestAnimationFrame(() => {
                    newlyAddedCards.forEach((card) => card.classList.add("is-visible"));
                });
            });
        }

        // The server clamps an out-of-range page to the last one, so a page
        // number that didn't advance means there is nothing further to load.
        const advanced = newState.currentPage > pageState.currentPage;
        pageState = Object.assign({}, newState, {
            hasMore: advanced && newState.hasMore,
            currentPage: Math.max(newState.currentPage, pageState.currentPage)
        });
        dom.root.dataset.currentPage = String(pageState.currentPage);
        dom.root.dataset.totalPages = String(pageState.totalPages);
        dom.root.dataset.hasMore = String(pageState.hasMore);

        setPaginationSuperseded(pageState.hasMore || pageState.currentPage > 1);
        if (!pageState.hasMore) {
            finishCatalog();
        }
        saveHistoryState();
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
            announce(dom.endOfResults.textContent.trim());
        }
    }

    // ---- error / skeleton UI ----

    function showError() {
        if (dom.errorBox) {
            dom.errorBox.hidden = false;
            const span = dom.errorBox.querySelector("span");
            announce(span ? span.textContent.trim() : "");
        }
        // Stop auto-retrying on scroll - Retry (or the numbered pager, shown
        // again as the fallback) is the way back in.
        if (sentinelObserver && dom.sentinel) {
            sentinelObserver.unobserve(dom.sentinel);
        }
        setPaginationSuperseded(false);
    }

    function hideError() {
        if (dom && dom.errorBox) {
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
        if (!dom || !dom.skeletonBatch) {
            return;
        }
        if (dom.skeletonBatch.childElementCount === 0) {
            buildSkeletonCards(dom.skeletonBatch);
        }
        dom.skeletonBatch.hidden = false;
    }

    function hideSkeleton() {
        if (!dom || !dom.skeletonBatch) {
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
                '<div class="skeleton-block skeleton-line skeleton-line-title-2"></div>' +
                '<div class="skeleton-block skeleton-line skeleton-line-price"></div>';
            container.appendChild(card);
        }
    }

    // ---- scrollspy (only on the unfiltered "All" view without a search) ----
    //
    // The active category is the section crossing a reading line just
    // under the sticky category bar. Watching whole sections (not just
    // their headers) keeps it correct in the middle of a long category and
    // when scrolling back up, where no header crosses the viewport.

    function setupScrollspy() {
        spyActive = null;
        spyVisible = new Set();
        spyLinks = new Map();
        clearSpyHighlight();

        const spyEligible = pageState.activeCategory === "all" && !pageState.searchQuery && !!dom.categoryBar;
        if (!spyEligible) {
            return;
        }
        dom.categoryBar.querySelectorAll(".shop-category-link").forEach((link) => {
            try {
                const category = new URL(link.href, window.location.origin).searchParams.get("category");
                if (category && category !== "all") {
                    spyLinks.set(category, link);
                }
            } catch (error) {
                // malformed href - that chip just won't be highlighted
            }
        });

        spyLine = Math.round(dom.categoryBar.getBoundingClientRect().height) + SPY_LINE_GAP_PX;
        spyObserver = new IntersectionObserver(handleScrollspy, {
            rootMargin: "-" + spyLine + "px 0px " + SPY_BAND_BOTTOM + " 0px",
            threshold: 0
        });
        dom.root.querySelectorAll(".category-section").forEach((section) => spyObserver.observe(section));
    }

    function handleScrollspy(entries) {
        entries.forEach((entry) => {
            if (entry.isIntersecting) {
                spyVisible.add(entry.target);
            } else {
                spyVisible.delete(entry.target);
            }
        });

        // Prefer the section that actually covers the reading line; while
        // the line sits in the small gap between two sections, the next
        // one down. A single owner at any scroll position = no flicker.
        let chosen = null;
        let firstBelow = null;
        let firstBelowTop = Infinity;
        spyVisible.forEach((section) => {
            if (!section.isConnected) {
                spyVisible.delete(section);
                return;
            }
            const rect = section.getBoundingClientRect();
            if (rect.top <= spyLine + 1 && rect.bottom > spyLine) {
                chosen = section;
            } else if (rect.top > spyLine && rect.top < firstBelowTop) {
                firstBelow = section;
                firstBelowTop = rect.top;
            }
        });
        if (!chosen) {
            chosen = firstBelow;
        }
        const category = chosen ? chosen.dataset.category : null;
        if (!category || category === spyActive) {
            if (!category && spyVisible.size === 0 && isAboveFirstSection()) {
                spyActive = null;
                clearSpyHighlight();
            }
            return;
        }
        spyActive = category;
        highlightSpyCategory(category);
    }

    function isAboveFirstSection() {
        const first = dom && dom.root.querySelector(".category-section");
        return !!first && first.getBoundingClientRect().top > spyLine;
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
        const link = spyLinks.get(category);
        if (!link) {
            return;
        }
        link.classList.add("is-in-view");
        revealChip(link);
    }

    // Scroll the bar horizontally just enough to show the chip. Only the
    // bar's own scrollLeft changes - element.scrollIntoView() can also nudge
    // the page and interrupts momentum scrolling on iOS Safari.
    function revealChip(link) {
        const bar = dom.categoryBar;
        const barRect = bar.getBoundingClientRect();
        const linkRect = link.getBoundingClientRect();
        const leftPad = 12;
        const rightPad = 44; // clears the right-edge "more" fade
        let delta = 0;
        if (linkRect.left < barRect.left + leftPad) {
            delta = linkRect.left - barRect.left - leftPad;
        } else if (linkRect.right > barRect.right - rightPad) {
            delta = linkRect.right - barRect.right + rightPad;
        }
        if (delta !== 0) {
            bar.scrollBy({ left: delta, behavior: prefersReducedMotion() ? "auto" : "smooth" });
        }
    }

    // ---- browsing-position persistence (per history entry) ----
    //
    // How far the customer has loaded and where they were reading is kept
    // in this history entry's state (history.replaceState, URL untouched),
    // so back/forward and reload return to the same products and position.
    // The URL deliberately no longer gets ?page=N written into it: on
    // return the server would render only page N, dropping everything
    // above it.

    function stateKey() {
        return pageState.activeCategory + "\u0001" + pageState.searchQuery;
    }

    function findReadingAnchor() {
        const line = spyLine || 60;
        const cards = dom.root.querySelectorAll(".product-card[data-product-id]");
        for (let i = 0; i < cards.length; i += 1) {
            const top = cards[i].getBoundingClientRect().top;
            if (top >= line) {
                return { id: cards[i].dataset.productId, top: Math.round(top) };
            }
        }
        return null;
    }

    function saveHistoryState() {
        if (!dom || !pageState) {
            return;
        }
        const anchor = findReadingAnchor();
        const entry = {
            key: stateKey(),
            page: pageState.currentPage,
            scrollY: Math.round(window.scrollY),
            anchorId: anchor ? anchor.id : null,
            anchorTop: anchor ? anchor.top : null
        };
        try {
            const next = Object.assign({}, window.history.state || {});
            next[HISTORY_STATE_KEY] = entry;
            window.history.replaceState(next, "");
        } catch (error) {
            // History API blocked/rate-limited - position just won't be restored.
        }
    }

    function scheduleScrollSave() {
        if (scrollSaveTimer) {
            window.clearTimeout(scrollSaveTimer);
        }
        // Debounced (saves once scrolling settles) - Safari throttles
        // replaceState to ~100 calls per 30s.
        scrollSaveTimer = window.setTimeout(() => {
            scrollSaveTimer = null;
            saveHistoryState();
        }, SCROLL_SAVE_DEBOUNCE_MS);
    }

    function readSavedState() {
        const state = window.history.state;
        const saved = state && state[HISTORY_STATE_KEY];
        return saved && saved.key === stateKey() ? saved : null;
    }

    async function fetchPagesInOrder(pages, signal) {
        const results = new Array(pages.length);
        let next = 0;
        let failed = false;
        const worker = async () => {
            while (next < pages.length && !failed) {
                const index = next;
                next += 1;
                try {
                    results[index] = await fetchPage(pages[index], signal); // eslint-disable-line no-await-in-loop
                } catch (error) {
                    failed = true;
                    if (error && error.name === "AbortError") {
                        throw error;
                    }
                }
            }
        };
        const workers = [];
        for (let i = 0; i < Math.min(RESTORE_CONCURRENCY, pages.length); i += 1) {
            workers.push(worker());
        }
        await Promise.all(workers);
        // Only a contiguous run from the first page can be appended in order.
        const contiguous = [];
        for (let i = 0; i < results.length && results[i]; i += 1) {
            contiguous.push(results[i]);
        }
        return { roots: contiguous, complete: contiguous.length === pages.length };
    }

    async function restoreBrowsingPosition() {
        const saved = readSavedState();
        if (!saved) {
            return;
        }
        const restoreGeneration = generation;
        const target = Math.min(saved.page, pageState.totalPages, pageState.currentPage + RESTORE_MAX_EXTRA_PAGES);

        if (target > pageState.currentPage) {
            const pages = [];
            for (let p = pageState.currentPage + 1; p <= target; p += 1) {
                pages.push(p);
            }
            // isLoading blocks the sentinel for the duration, so no
            // duplicate request can race the restore.
            isLoading = true;
            scheduleSkeleton();
            const controller = new AbortController();
            fetchController = controller;
            let complete = true;
            try {
                const result = await fetchPagesInOrder(pages, controller.signal);
                if (restoreGeneration !== generation) {
                    return;
                }
                result.roots.forEach((root) => appendPage(root, { animate: false }));
                complete = result.complete;
            } catch (error) {
                if (restoreGeneration !== generation) {
                    return;
                }
                complete = false;
            } finally {
                if (restoreGeneration === generation) {
                    isLoading = false;
                    fetchController = null;
                    cancelSkeleton();
                }
            }
            if (!complete) {
                showError();
            } else {
                rearmSentinel();
            }
        }

        const anchorCard = saved.anchorId
            ? dom.root.querySelector('.product-card[data-product-id="' + cssEscape(saved.anchorId) + '"]')
            : null;
        if (anchorCard && typeof saved.anchorTop === "number") {
            const y = anchorCard.getBoundingClientRect().top + window.scrollY - saved.anchorTop;
            jumpTo(y);
        } else {
            jumpTo(saved.scrollY || 0);
        }
    }

    // Programmatic jumps (restoring a position, landing on new results)
    // must be instant: smooth-ui.css sets scroll-behavior: smooth on <html>,
    // which otherwise turns these into long animated glides.
    function jumpTo(y) {
        try {
            window.scrollTo({ top: Math.max(0, y), left: 0, behavior: "instant" });
        } catch (error) {
            window.scrollTo(0, Math.max(0, y));
        }
    }

    // A fresh category/search choice starts at the top of the results (the
    // category bar plus the first heading), not wherever the previous,
    // usually much longer, list had been scrolled to.
    function revealResultsTop() {
        // The bar search box (#barSearch) is pinned over the top of the
        // screen while open - land the results just below it.
        const pinned = document.querySelector(".bar-search:not([hidden])");
        const offset = pinned ? pinned.getBoundingClientRect().height : 0;
        const top = dom.root.getBoundingClientRect().top + window.scrollY - offset;
        if (window.scrollY > top) {
            jumpTo(top - 4);
        }
    }

    function navigationType() {
        try {
            const entry = performance.getEntriesByType("navigation")[0];
            return entry ? entry.type : "navigate";
        } catch (error) {
            return "navigate";
        }
    }

    // ---- boot ----

    if ("scrollRestoration" in window.history) {
        // This module restores the position itself, once the earlier
        // batches are back on the page; the browser's own attempt would
        // land in content that hasn't been loaded yet.
        window.history.scrollRestoration = "manual";
    }
    window.addEventListener("scroll", scheduleScrollSave, { passive: true });
    window.addEventListener("pagehide", saveHistoryState);
    document.addEventListener("visibilitychange", () => {
        if (document.visibilityState === "hidden") {
            saveHistoryState();
        }
    });

    let spyResizeTimer = null;
    window.addEventListener("resize", () => {
        if (spyResizeTimer) {
            window.clearTimeout(spyResizeTimer);
        }
        spyResizeTimer = window.setTimeout(() => {
            spyResizeTimer = null;
            if (!dom || !spyObserver || !dom.categoryBar) {
                return;
            }
            const line = Math.round(dom.categoryBar.getBoundingClientRect().height) + SPY_LINE_GAP_PX;
            if (line !== spyLine) {
                spyObserver.disconnect();
                spyObserver = null;
                setupScrollspy();
            }
        }, 200);
    }, { passive: true });

    window.MaeMayaShop = Object.assign(window.MaeMayaShop || {}, {
        saveBrowsingPosition: saveHistoryState
    });

    // The page stays hidden (html.is-restoring, set before first paint by
    // the inline script in index.html) until the restore has reloaded the
    // earlier products and jumped to the saved spot - so it appears there
    // directly instead of flashing the top of the list first.
    function revealPage() {
        document.documentElement.classList.remove("is-restoring");
    }

    function restoreThenReveal() {
        let restore;
        try {
            restore = Promise.resolve(restoreBrowsingPosition());
        } catch (error) {
            restore = Promise.resolve();
        }
        return restore.catch(() => {}).then(revealPage);
    }

    init();
    if (dom) {
        const type = navigationType();
        if (type === "back_forward" || type === "reload") {
            restoreThenReveal();
        } else {
            revealPage();
        }
    } else {
        revealPage();
    }

    document.addEventListener("shop:resultsReplaced", (event) => {
        teardownForNavigation();
        init();
        if (!dom) {
            return;
        }
        if (event.detail && event.detail.restore) {
            // Back/forward inside the page (e.g. from a category to "All"):
            // same treatment - hide until the saved spot is back.
            const saved = readSavedState();
            if (saved && (saved.page > pageState.currentPage || saved.scrollY > 0)) {
                document.documentElement.classList.add("is-restoring");
            }
            restoreThenReveal();
        } else {
            revealResultsTop();
            saveHistoryState();
        }
    });
})();
