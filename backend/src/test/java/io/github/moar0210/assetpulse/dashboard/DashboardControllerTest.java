package io.github.moar0210.assetpulse.dashboard;

import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DashboardController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ApiProblemWriter.class)
class DashboardControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private DashboardService service;

    @Test
    void databaseFailuresReturnAGenericNoStoreUnavailableProblem() throws Exception {
        when(service.get(nullable(AuthenticatedActor.class)))
                .thenThrow(new DataAccessResourceFailureException("sensitive database detail"));

        mockMvc.perform(get("/api/v1/dashboard").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.code").value("DASHBOARD_UNAVAILABLE"))
                .andExpect(jsonPath("$.title").value("Dashboard unavailable"))
                .andExpect(
                        jsonPath("$.detail")
                                .value(
                                        "The dashboard is temporarily unavailable. Try again later."))
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.not(
                                                org.hamcrest.Matchers.containsString(
                                                        "sensitive database detail"))));
    }
}
