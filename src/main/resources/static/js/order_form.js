(function() {
    const lookupUrl =
        document.querySelector('meta[name="postal-lookup-url"]')?.getAttribute("content") ||
        "/api/postal-lookup";
    const lookupBtn = document.getElementById("postalLookupBtn");
    const zipInput = document.getElementById("zipCode");
    const addressInput = document.getElementById("address");
    const messageBox = document.getElementById("postalLookupMessage");

    if (!lookupBtn || !zipInput || !addressInput || !messageBox) {
        return;
    }

    const fallbackNotFound =
        document.querySelector('meta[name="postal-lookup-not-found-message"]')?.getAttribute("content") ||
        "Address not found.";
    const fallbackFailed =
        document.querySelector('meta[name="postal-lookup-failed-message"]')?.getAttribute("content") ||
        "Could not search address right now.";

    function setMessage(text, success) {
        messageBox.textContent = text || "";
        messageBox.style.color = success ? "#1b5e20" : "#c62828";
    }

    lookupBtn.addEventListener("click", async () => {
        const zipCode = (zipInput.value || "").trim();
        setMessage("", false);
        lookupBtn.disabled = true;

        try {
            const response = await fetch(`${lookupUrl}?zipCode=${encodeURIComponent(zipCode)}`, {
                method: "GET",
                headers: { "Accept": "application/json" }
            });
            const data = await response.json();

            if (data.success) {
                if (data.zipCode) {
                    zipInput.value = data.zipCode;
                }
                if (data.address) {
                    addressInput.value = data.address;
                }
                setMessage(data.message || "", true);
            } else {
                setMessage(data.message || fallbackNotFound, false);
            }
        } catch (error) {
            setMessage(fallbackFailed, false);
        } finally {
            lookupBtn.disabled = false;
        }
    });
})();
