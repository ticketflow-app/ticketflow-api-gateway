package ticketflow.api_gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import reactor.core.publisher.Mono;

@Configuration
public class RateLimitConfig {

	@Bean
	public KeyResolver ipKeyResolver() {
		return exchange -> {
			String ip = null;
			if (exchange.getRequest().getRemoteAddress() != null) {
				ip = exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
			}
			return Mono.just(ip != null ? ip : "unknown");
		};
	}

}