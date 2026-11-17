package com.ainvr.web.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** /api/about：版本、启动时间与运行时长；不依赖鉴权链路（standalone 装配）。 */
class AboutWebTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AboutController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void returnsVersionAndUptime() throws Exception {
        String body = mvc.perform(get("/api/about"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        // 解析数值后在 Java 侧断言，避免 JSON 整数被映射为 Integer 与
        // Hamcrest greaterThan(Long) 比较时触发 ClassCastException。
        Map<?, ?> map = new ObjectMapper().readValue(body, Map.class);
        long startTime = ((Number) map.get("startTime")).longValue();
        long uptimeMs = ((Number) map.get("uptimeMs")).longValue();
        assertTrue(startTime > 0, "startTime 应为正数");
        assertTrue(uptimeMs >= 0, "uptimeMs 应非负");
    }
}
