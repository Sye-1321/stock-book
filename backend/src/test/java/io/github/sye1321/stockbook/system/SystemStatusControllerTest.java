package io.github.sye1321.stockbook.system;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SystemStatusController.class)
class SystemStatusControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DatabaseStatusChecker databaseStatusChecker;

    @Test
    void returnsOkWhenTheDatabaseIsAvailable() throws Exception {
        given(databaseStatusChecker.isAvailable()).willReturn(true);

        mockMvc.perform(get("/api/v1/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database").value("UP"));
    }

    @Test
    void returnsServiceUnavailableWhenTheDatabaseIsUnavailable() throws Exception {
        given(databaseStatusChecker.isAvailable()).willReturn(false);

        mockMvc.perform(get("/api/v1/status"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.database").value("DOWN"));
    }

    @Test
    void livenessDoesNotCheckTheDatabase() throws Exception {
        mockMvc.perform(get("/api/v1/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database").value("NOT_CHECKED"));

        then(databaseStatusChecker).shouldHaveNoInteractions();
    }
}
