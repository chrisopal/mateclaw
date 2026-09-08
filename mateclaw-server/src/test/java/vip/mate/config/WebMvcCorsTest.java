package vip.mate.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.DefaultCorsProcessor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import static org.junit.jupiter.api.Assertions.*;

class WebMvcCorsTest {
    @Test void suggestionPatchPreflightRespectsConfiguredOrigin() throws Exception {
        WebMvcConfig config = new WebMvcConfig(null, null);
        ReflectionTestUtils.setField(config, "allowedOrigins", "http://127.0.0.1:5176");
        ExposedRegistry registry = new ExposedRegistry();
        config.addCorsMappings(registry);
        CorsConfiguration cors = registry.api();
        MockHttpServletResponse allowed = preflight(cors, "http://127.0.0.1:5176");
        assertEquals(200, allowed.getStatus());
        assertTrue(allowed.getHeader("Access-Control-Allow-Methods").contains("PATCH"));
        assertEquals("http://127.0.0.1:5176", allowed.getHeader("Access-Control-Allow-Origin"));
        assertEquals(403, preflight(cors, "https://unapproved.example").getStatus());
    }

    private MockHttpServletResponse preflight(CorsConfiguration cors, String origin) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/v1/semantic/graphs/1/suggestions/2");
        request.addHeader("Origin", origin);
        request.addHeader("Access-Control-Request-Method", "PATCH");
        request.addHeader("Access-Control-Request-Headers", "authorization,x-workspace-id,content-type");
        MockHttpServletResponse response = new MockHttpServletResponse();
        new DefaultCorsProcessor().processRequest(cors, request, response);
        return response;
    }

    private static class ExposedRegistry extends CorsRegistry {
        CorsConfiguration api() { return getCorsConfigurations().get("/api/**"); }
    }
}
