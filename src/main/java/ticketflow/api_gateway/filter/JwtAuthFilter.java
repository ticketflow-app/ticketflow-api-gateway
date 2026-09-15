package ticketflow.api_gateway.filter;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class JwtAuthFilter implements GlobalFilter, Ordered {

	private static final List<String> RUTAS_PUBLICAS = List.of("/register", "/login", "/actuator");

	private final SecretKey secretKey;

	public JwtAuthFilter(@Value("${jwt.secret}") String secret) {
		this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
	}

	@Override
	public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
		String path = exchange.getRequest().getURI().getPath();
		HttpMethod method = exchange.getRequest().getMethod();

		if (esRutaPublica(path) || HttpMethod.OPTIONS.equals(method)) {
			return chain.filter(exchange);
		}

		String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
		if (authHeader == null || !authHeader.startsWith("Bearer ")) {
			return noAutorizado(exchange);
		}

		try {
			Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(authHeader.substring(7));
			return chain.filter(exchange);
		} catch (JwtException | IllegalArgumentException e) {
			return noAutorizado(exchange);
		}
	}

	private boolean esRutaPublica(String path) {
		return RUTAS_PUBLICAS.stream().anyMatch(path::startsWith);
	}

	private Mono<Void> noAutorizado(ServerWebExchange exchange) {
		exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
		return exchange.getResponse().setComplete();
	}

	@Override
	public int getOrder() {
		return -1;
	}
}