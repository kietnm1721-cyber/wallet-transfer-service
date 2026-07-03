package com.wallet.shared.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class MdcFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        try {
            String requestId   = httpRequest.getHeader("X-Request-Id");
            String requestorId = httpRequest.getHeader("X-Requestor-Id");
            if (requestId   != null) MDC.put("requestId",   requestId);
            if (requestorId != null) MDC.put("requestorId", requestorId);
            chain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
}
