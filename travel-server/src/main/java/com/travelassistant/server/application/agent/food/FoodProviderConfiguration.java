package com.travelassistant.server.application.agent.food;

import com.travelassistant.server.application.agent.spi.AgentDescriptor;
import com.travelassistant.server.application.agent.spi.AgentProvider;
import com.travelassistant.server.application.agent.spi.BuiltinServiceProvider;
import com.travelassistant.server.application.agent.spi.ContractRules;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把内置美食能力注册成 SPI Provider。
 *
 * <p>内置 Bean 走同一套 G0（{@code builtin.} 保留前缀），不需要 HTTP 端点与 G1 至 G4
 * 探针；但它与配置式 HTTP Provider 的响应一样，每次都要过 {@code ContractValidator}。
 * 注册表会把声明 {@code FOOD_RECOMMEND} 的内置 Bean 设为默认 primary；配置了外部路由时
 * 由外部 Provider 覆盖，内置 Bean 自动留在该 intent 的 fallback 链上。
 */
@Configuration
public class FoodProviderConfiguration {

  @Bean
  public AgentProvider builtinFood(ContractRules rules, FoodRecommendationService food) {
    AgentDescriptor descriptor = new AgentDescriptor("builtin.food", "1.0.0",
        rules.contractVersion(), Set.of("FOOD_RECOMMEND"), "BEAN", "NONE", true,
        AgentDescriptor.ACTIVE, null);
    return new BuiltinServiceProvider(descriptor, food::recommend);
  }
}
