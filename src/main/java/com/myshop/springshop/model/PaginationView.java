package com.myshop.springshop.model;

import java.util.List;

public record PaginationView<T>(
        List<T> items,
        int currentPage,
        int totalPages,
        int pageSize,
        int totalItems,
        int startItem,
        int endItem
) {

    public boolean hasItems() {
        return totalItems > 0;
    }

    public boolean hasPrevious() {
        return currentPage > 1;
    }

    public boolean hasNext() {
        return currentPage < totalPages;
    }

    public int previousPage() {
        return Math.max(1, currentPage - 1);
    }

    public int nextPage() {
        return Math.min(totalPages, currentPage + 1);
    }
}
