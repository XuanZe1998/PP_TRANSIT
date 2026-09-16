package com.transit.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;

/** Makes the locale/currency contract explicit on every browser API response. */
@Component
public class LocalizationContextFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String language = request.getHeader("Accept-Language");
        String requestedCurrency = request.getHeader("X-Display-Currency");
        boolean english = (language != null && language.toLowerCase(Locale.ROOT).startsWith("en"))
                || "USD".equalsIgnoreCase(requestedCurrency);
        response.setHeader("Content-Language", english ? "en-US" : "zh-CN");
        response.setHeader("X-Display-Currency", english ? "USD" : "CNY");
        response.addHeader("Vary", "Accept-Language, X-Display-Currency");
        chain.doFilter(request, response);
    }
}
