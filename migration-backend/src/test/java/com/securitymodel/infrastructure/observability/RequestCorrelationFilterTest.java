package com.securitymodel.infrastructure.observability;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestCorrelationFilterTest {
    private final RequestCorrelationFilter filter = new RequestCorrelationFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void acceptsBoundedClientRequestIdAndClearsItAfterTheRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/task-progress");
        request.addHeader(RequestCorrelationFilter.HEADER, "req-12345678");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            assertTrue("req-12345678".equals(MDC.get("requestId")));
        };

        filter.doFilter(request, response, chain);

        assertTrue(response.getHeader(RequestCorrelationFilter.HEADER).equals("req-12345678"));
        assertTrue(MDC.get("requestId") == null);
    }

    @Test
    void replacesUnsafeClientIdAndAlwaysReturnsAnId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/task-progress");
        request.addHeader(RequestCorrelationFilter.HEADER, "bad id with spaces");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertDoesNotThrow(() -> filter.doFilter(request, response, (req, res) -> { }));

        String returned = response.getHeader(RequestCorrelationFilter.HEADER);
        assertTrue(returned != null && !returned.contains(" "));
    }
}
