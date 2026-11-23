package io.github.sye1321.stockbook.system;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class SystemStatusController {

    private final DatabaseStatusChecker databaseStatusChecker;

    public SystemStatusController(DatabaseStatusChecker databaseStatusChecker) {
        this.databaseStatusChecker = databaseStatusChecker;
    }

    @GetMapping("/status")
    public ResponseEntity<StatusResponse> status() {
        boolean databaseAvailable = databaseStatusChecker.isAvailable();
        HttpStatus httpStatus = databaseAvailable ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        String status = databaseAvailable ? "UP" : "DOWN";

        return ResponseEntity.status(httpStatus)
                .body(new StatusResponse("Stock Book", status, status));
    }

    @GetMapping("/liveness")
    public StatusResponse liveness() {
        return new StatusResponse("Stock Book", "UP", "NOT_CHECKED");
    }

    public record StatusResponse(String application, String status, String database) {}
}
