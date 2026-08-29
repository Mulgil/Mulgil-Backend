package com.mulgil.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulgil.auth.JwtService;
import com.mulgil.common.error.ApiError;
import com.mulgil.common.error.ApiException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
final class BearerTokenFilter extends OncePerRequestFilter {
    private final JwtService jwt;
    private final ObjectMapper objectMapper;

    BearerTokenFilter(JwtService jwt, ObjectMapper objectMapper) {
        this.jwt = jwt;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization == null) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!authorization.startsWith("Bearer ")) {
            writeUnauthenticated(response);
            return;
        }
        try {
            UUID userId = jwt.verify(authorization.substring(7));
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(userId, null, List.of()));
            filterChain.doFilter(request, response);
        } catch (ApiException exception) {
            SecurityContextHolder.clearContext();
            writeUnauthenticated(response);
        }
    }

    private void writeUnauthenticated(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(),
                new ApiError("UNAUTHENTICATED", "Authentication failed.", Map.of()));
    }
}
