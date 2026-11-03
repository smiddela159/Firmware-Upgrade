package com.att.device.management.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.util.ContentCachingRequestWrapper;
import java.io.IOException;

/**
 * Wraps the incoming {@link HttpServletRequest} so that the request body can be read multiple times.
 * This is required for the idempotency interceptor to compute a hash of the payload without
 * consuming the InputStream before the controller runs.
 */
@Component
public class RequestCachingFilter implements Filter {
    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest httpReq = (HttpServletRequest) req;
        ContentCachingRequestWrapper wrapper = new ContentCachingRequestWrapper(httpReq);
        chain.doFilter(wrapper, res);
    }
}

