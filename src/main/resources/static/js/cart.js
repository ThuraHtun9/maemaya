document.addEventListener("DOMContentLoaded", function () {
    const wrapper = document.querySelector(".cart-container");
    if (!wrapper) {
        return;
    }

    // တစ်ကြိမ်တည်း request ပဲ run ခွင့်ပြုမယ် (ခလုတ်ကို မြန်မြန်ဆက်နှိပ်ရင် data ရောထွေးမသွားအောင်)
    let busy = false;

    // Cart fragment ကိုပဲ ပြန်ခေါ်ပြီး #cartContent ကို အစားထိုးမယ် (page တစ်ခုလုံး reload မလုပ်ဘူး)
    function refreshCart() {
        return fetch(window.location.pathname, {
            headers: { "X-Requested-With": "XMLHttpRequest" }
        })
            .then(function (response) { return response.text(); })
            .then(function (html) {
                const parser = new DOMParser();
                const doc = parser.parseFromString(html, "text/html");
                const newContent = doc.getElementById("cartContent");
                const oldContent = document.getElementById("cartContent");
                if (newContent && oldContent) {
                    oldContent.replaceWith(newContent);
                }
            });
    }

    // +1 / -1 ကို server ဆီ AJAX နဲ့ ပို့မယ် (/cart/add, /cart/remove ကတော့ index.html မှာ သုံးနေပြီးသားပါ)
    function changeQuantity(productId, action) {
        if (busy) {
            return;
        }
        busy = true;
        const url = action === "increase" ? "/cart/add" : "/cart/remove";
        const body = new URLSearchParams();
        body.set("productId", productId);
        body.set("quantity", "1");

        fetch(url, {
            method: "POST",
            headers: { "Content-Type": "application/x-www-form-urlencoded" },
            body: body.toString()
        })
            .then(refreshCart)
            .finally(function () {
                busy = false;
            });
    }

    // .cart-container ပေါ်မှာ click listener တစ်ခုတည်းထားမယ် (event delegation)
    // — ဒါမှ #cartContent ကို AJAX နဲ့ အစားထိုးတိုင်း listener အသစ် ပြန်မချိတ်ရတော့ဘူး
    wrapper.addEventListener("click", function (event) {
        const btn = event.target.closest(".qty-btn");
        if (!btn) {
            return;
        }
        const stepper = btn.closest(".qty-stepper");
        if (!stepper) {
            return;
        }
        const productId = stepper.getAttribute("data-product-id");
        const action = btn.classList.contains("qty-btn-plus") ? "increase" : "decrease";
        changeQuantity(productId, action);
    });
});