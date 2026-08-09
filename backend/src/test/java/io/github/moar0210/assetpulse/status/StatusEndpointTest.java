package io.github.moar0210.assetpulse.status;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.moar0210.assetpulse.security.ApiProblemWriter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(StatusController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ApiProblemWriter.class)
class StatusEndpointTest {

    @Autowired private MockMvc mockMvc;

    @Test
    void getStatusReturnsThePublicAvailabilityContract() throws Exception {
        mockMvc.perform(get("/api/v1/status"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().string("{\"status\":\"available\"}"));
    }

    @Test
    void statusEndpointRejectsWrites() throws Exception {
        mockMvc.perform(post("/api/v1/status")).andExpect(status().isMethodNotAllowed());
    }
}
