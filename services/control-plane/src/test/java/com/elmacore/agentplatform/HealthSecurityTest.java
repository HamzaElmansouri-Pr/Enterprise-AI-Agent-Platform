package com.elmacore.agentplatform;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class HealthSecurityTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void anonymousHealthExposesOnlyStatus() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}", JsonCompareMode.STRICT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/unrelated", "/actuator", "/actuator/env", "/actuator/info",
            "/actuator/health/details", "/login"})
    void unspecifiedRoutesDenyAnonymousAccess(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isForbidden());
    }

    @Test
    void healthDoesNotAllowStateChangingRequests() throws Exception {
        mockMvc.perform(post("/actuator/health")).andExpect(status().isForbidden());
    }
}
