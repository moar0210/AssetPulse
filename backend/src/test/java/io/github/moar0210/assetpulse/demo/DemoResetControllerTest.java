package io.github.moar0210.assetpulse.demo;

import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.moar0210.assetpulse.identity.AuthenticatedActor;
import io.github.moar0210.assetpulse.security.ApiProblemWriter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.TransactionSystemException;

@WebMvcTest(DemoResetController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ApiProblemWriter.class)
class DemoResetControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private DemoResetService service;

    @Test
    void boundedResetRejectionReturnsAStableConflictProblem() throws Exception {
        when(service.reset(nullable(AuthenticatedActor.class), nullable(String.class)))
                .thenThrow(new DemoResetLimitExceededException());

        mockMvc.perform(post("/api/v1/demo/reset").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("DEMO_RESET_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.title").value("Demo reset limit exceeded"));
    }

    @Test
    void uncertainResetReturnsAStableUnavailableProblem() throws Exception {
        when(service.reset(nullable(AuthenticatedActor.class), nullable(String.class)))
                .thenThrow(new TransactionSystemException("sensitive commit detail"));

        mockMvc.perform(post("/api/v1/demo/reset").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.code").value("DEMO_RESET_UNAVAILABLE"))
                .andExpect(jsonPath("$.title").value("Demo reset unavailable"));
    }
}
