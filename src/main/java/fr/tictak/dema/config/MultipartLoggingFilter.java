package fr.tictak.dema.config;


import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;


import java.io.IOException;

@Component
public class MultipartLoggingFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(MultipartLoggingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (request.getContentType() != null && request.getContentType().startsWith("multipart/form-data")) {
            logger.info("Multipart request received for {}: Content-Type={}", request.getRequestURI(), request.getContentType());
        }
        filterChain.doFilter(request, response);
    }
}