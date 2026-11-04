package com.opspulse.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Spring Security's defaults answer 401/403 with an HTML page or an empty body.
 * An API client (and the React app) wants the same RFC 7807 JSON shape that
 * every other error uses, so both handlers write a ProblemDetail.
 */
@Component
@RequiredArgsConstructor
public class JsonSecurityHandlers {

    private final ObjectMapper objectMapper;

    public AuthenticationEntryPoint entryPoint() {
        return (req, res, ex) -> write(res, HttpStatus.UNAUTHORIZED, "Authentication required");
    }

    public AccessDeniedHandler accessDenied() {
        return (req, res, ex) -> write(res, HttpStatus.FORBIDDEN, "You do not have permission to do this");
    }

    private void write(HttpServletResponse res, HttpStatus status, String detail) throws IOException {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(res.getOutputStream(), body);
    }
}
