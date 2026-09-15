package ticketflow.api_gateway.fallback;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
public class FallbackController {

	@GetMapping("/fallback/users")
	public ResponseEntity<Map<String, Object>> usersFallback() {
		return fallback("user-service");
	}

	@GetMapping("/fallback/tickets")
	public ResponseEntity<Map<String, Object>> ticketsFallback() {
		return fallback("ticket-service");
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