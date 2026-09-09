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
    (function () {
        const normalize = (value) => (value || "").toString().toLowerCase().trim();
        const input = document.querySelector('[data-filter-input="products"]');
        const stockSelect = document.querySelector('[data-filter-stock="products"]');
        const resetBtn = document.querySelector('[data-filter-reset="products"]');
        const result = document.querySelector('[data-filter-result="products"]');
        const rows = Array.from(document.querySelectorAll("tr.product-row"));
        const apply = () => {
            const keyword = normalize(input ? input.value : "");
            const status = normalize(stockSelect ? stockSelect.value : "all");
            let visible = 0;
            rows.forEach((row) => {
                const haystack = normalize((row.dataset.productName || "") + " " + (row.dataset.productCategory || ""));
                const keywordMatch = keyword.length === 0 || haystack.includes(keyword);
                const stock = Number(row.dataset.productStock || "0");
                const statusMatch = status === "all" || (status === "in" && stock > 0) || (status === "low" && stock > 0 && stock < 5) || (status === "out" && stock <= 0);
                const show = keywordMatch && statusMatch;
                row.hidden = !show;
                if (show) visible += 1;
            });
            if (result) result.textContent = `${visible} / ${rows.length} shown`;
        };
        if (input) input.addEventListener("input", apply, { passive: true });
        if (stockSelect) stockSelect.addEventListener("change", apply);
        if (resetBtn) resetBtn.addEventListener("click", () => {
            if (input) input.value = "";
            if (stockSelect) stockSelect.selectedIndex = 0;
            apply();
        });
        apply();
    }());
