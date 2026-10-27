package ticketflow.api_gateway.fallback;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
public class FallbackController {

	@RequestMapping("/fallback/users")
	public ResponseEntity<Map<String, Object>> usersFallback() {
		return fallback("user-service");
	}

	@RequestMapping("/fallback/inventory")
	public ResponseEntity<Map<String, Object>> inventoryFallback() {
		return fallback("inventory-service");
	}

	@RequestMapping("/fallback/catalog")
	public ResponseEntity<Map<String, Object>> catalogFallback() {
		return fallback("catalog-service");
	}

	private ResponseEntity<Map<String, Object>> fallback(String service) {
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
				.body(Map.of(
						"timestamp", Instant.now().toString(),
						"status", 503,
						"service", service,
						"message", "El servicio no está disponible en este momento. Intente más tarde."
				));
	}

}