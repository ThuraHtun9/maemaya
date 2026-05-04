package com.myshop.springshop.model;

public record ProductRequestRecord(
        long id,
        String requesterName,
        String contact,
        String productName,
        String note,
        String referenceImage,
        String requestStatus,
        String requestDate
) {
    public boolean hasImage() {
        return referenceImage != null && !referenceImage.isBlank();
    }

    public boolean isNew() {
        return "NEW".equals(requestStatus);
    }
}
