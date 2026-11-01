package com.myshop.springshop.config;

import java.util.concurrent.ConcurrentHashMap;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
@Component
public class RateLimitInterceptor implements HandlerInterceptor {
	
	private static final int MAX_REQUESTS_PER_WINDOW = 15;
	private static final long WINDOW_MS = 5 * 60 * 1000L;
	
	private final ConcurrentHashMap<String, Window> requestsByIp = new ConcurrentHashMap<>();
	
	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)throws Exception {
		String ip = request.getRemoteAddr();
		long now = System.currentTimeMillis();
		
		Window window = requestsByIp.computeIfAbsent(ip, k -> new Window(now));
		synchronized(window) {
			if (now - window.start > WINDOW_MS) {
				window.start = now;
				window.count = 0;
			}
			window.count++;
			if(window.count > MAX_REQUESTS_PER_WINDOW) {
				response.setStatus(429);
				response.getWriter().write("Too many requests. Please Try Again Later.");
				return false;
			}
		}
		return true;
	}
	
	private static class Window {
		long start;
		int count;
		Window(long start) {
			this.start = start;
		}
	}
}