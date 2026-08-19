package com.yuyu.salmonmind;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Java 21 虚拟线程 Runtime Gate：通过真实嵌入式 HTTP 请求进入 Spring 管理线程，
 * 在同一调用链执行 Testcontainers PostgreSQL 查询，并直接用 Thread.isVirtual() 取证。
 * 测试控制器只存在于测试上下文，不增加生产诊断 API。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:tc:postgresql:17.10-alpine:///salmon_mind",
                "spring.datasource.username=test",
                "spring.datasource.password=test",
                "spring.threads.virtual.enabled=true",
                "spring.main.keep-alive=true",
                "salmon.conversation.cache.enabled=false"
        }
)
class VirtualThreadRuntimeIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void httpRequestAndJdbcQueryRunOnVirtualThread() {
        ResponseEntity<ThreadProbe> response = rest.getForEntity(
                "/test-only/virtual-thread-jdbc", ThreadProbe.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().requestThreadVirtual()).isTrue();
        assertThat(response.getBody().afterJdbcThreadVirtual()).isTrue();
        assertThat(response.getBody().databaseValue()).isEqualTo(1);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RuntimeGateConfiguration {

        @Bean
        RuntimeGateController runtimeGateController(JdbcTemplate jdbcTemplate) {
            return new RuntimeGateController(jdbcTemplate);
        }
    }

    @RestController
    static class RuntimeGateController {

        private final JdbcTemplate jdbcTemplate;

        RuntimeGateController(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        @GetMapping("/test-only/virtual-thread-jdbc")
        ThreadProbe probe() {
            boolean requestThreadVirtual = Thread.currentThread().isVirtual();
            Integer databaseValue = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return new ThreadProbe(requestThreadVirtual, Thread.currentThread().isVirtual(), databaseValue);
        }
    }

    record ThreadProbe(boolean requestThreadVirtual, boolean afterJdbcThreadVirtual, int databaseValue) {
    }
}
