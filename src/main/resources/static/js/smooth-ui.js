(function () {
    const doc = document;
    const root = doc.documentElement;
    const body = doc.body;
    if (!body) {
        return;
    }

    const reduceMotion = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    if (reduceMotion) {
        root.classList.remove("js-motion");
        body.classList.add("page-ready");
        return;
    }

    const leaveDelayMs = 170;
    let leaving = false;

    const markReady = function () {
        body.classList.remove("page-leaving");
        body.classList.add("page-ready");
    };

    if (doc.readyState === "complete" || doc.readyState === "interactive") {
        requestAnimationFrame(markReady);
    } else {
        doc.addEventListener("DOMContentLoaded", function () {
            requestAnimationFrame(markReady);
        }, { once: true });
    }

    const navigateWithFade = function (navigateFn) {
        if (leaving) {
            navigateFn();
            return;
        }
        leaving = true;
        body.classList.remove("page-ready");
        body.classList.add("page-leaving");
        window.setTimeout(function () {
            navigateFn();
        }, leaveDelayMs);
    };

    doc.addEventListener("click", function (event) {
        const link = event.target.closest("a[href]");
        if (!link) {
            return;
        }

        if (event.defaultPrevented || event.button !== 0) {
            return;
        }
        if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) {
            return;
        }
        if (link.target && link.target !== "_self") {
            return;
        }
        if (link.hasAttribute("download") || link.getAttribute("rel") === "external") {
            return;
        }
        if (link.hasAttribute("data-no-page-transition")) {
            return;
        }

        const rawHref = link.getAttribute("href");
        if (!rawHref || rawHref.startsWith("#") || rawHref.startsWith("javascript:")) {
            return;
        }

        const url = new URL(link.href, window.location.href);
        if (url.origin !== window.location.origin) {
            return;
        }

        const sameWithoutHash = url.pathname === window.location.pathname && url.search === window.location.search;
        if (sameWithoutHash) {
            return;
        }

        event.preventDefault();
        navigateWithFade(function () {
            window.location.assign(url.href);
        });
    });

    doc.addEventListener("submit", function (event) {
        const form = event.target;
        if (!(form instanceof HTMLFormElement)) {
            return;
        }
        if (form.hasAttribute("data-no-page-transition")) {
            return;
        }
        const method = (form.method || "get").toLowerCase();
        if (method !== "get") {
            return;
        }

        event.preventDefault();
        navigateWithFade(function () {
            form.submit();
        });
    });

    window.addEventListener("pageshow", function () {
        leaving = false;
        markReady();
    });
})();
