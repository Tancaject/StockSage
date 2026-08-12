package com.stocksage;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * StockSage 后端的 Spring Boot 启动入口。
 *
 * <p>{@link EnableScheduling} 会启用 RAG 维护任务，以及应用上下文中配置的所有定时采集器。</p>
 */
@EnableScheduling
@SpringBootApplication
public class StockSageApplication {

    /**
     * 启动 StockSage 后端应用。
     *
     * <p>调度能力由类上的 {@link EnableScheduling} 打开，应用启动后会加载 RAG 维护、定时采集和 IBKR 保活等任务。</p>
     *
     * @param args JVM 启动参数，Spring Boot 会继续解析其中的配置项
     */
    public static void main(String[] args) {
        SpringApplication.run(StockSageApplication.class, args);
    }
}
