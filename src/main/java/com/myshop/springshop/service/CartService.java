package com.myshop.springshop.service;

import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@Service
public class CartService {

    public static final String CART_SESSION_KEY = "cart";

    @SuppressWarnings("unchecked")
    public List<Long> getCart(HttpSession session) {
        Object cartObj = session.getAttribute(CART_SESSION_KEY);
        if (cartObj instanceof List<?>) {
            return (List<Long>) cartObj;
        }

        List<Long> cart = new ArrayList<>();
        session.setAttribute(CART_SESSION_KEY, cart);
        return cart;
    }

    public void add(HttpSession session, long productId) {
        addMany(session, productId, 1);
    }

    public int addMany(HttpSession session, long productId, int quantity) {
        if (quantity <= 0) {
            return 0;
        }
        List<Long> cart = getCart(session);
        for (int i = 0; i < quantity; i++) {
            cart.add(productId);
        }
        session.setAttribute(CART_SESSION_KEY, cart);
        return quantity;
    }

    public void removeOne(HttpSession session, long productId) {
        removeMany(session, productId, 1);
    }

    public int removeMany(HttpSession session, long productId, int quantity) {
        if (quantity <= 0) {
            return 0;
        }
        List<Long> cart = getCart(session);
        int removed = 0;
        Iterator<Long> iterator = cart.iterator();
        while (iterator.hasNext() && removed < quantity) {
            Long id = iterator.next();
            if (id != null && id == productId) {
                iterator.remove();
                removed++;
            }
        }
        session.setAttribute(CART_SESSION_KEY, cart);
        return removed;
    }

    public int count(HttpSession session) {
        return getCart(session).size();
    }

    public int countByProduct(HttpSession session, long productId) {
        int count = 0;
        for (Long id : getCart(session)) {
            if (id != null && id == productId) {
                count++;
            }
        }
        return count;
    }

    public void clear(HttpSession session) {
        session.removeAttribute(CART_SESSION_KEY);
    }
}
