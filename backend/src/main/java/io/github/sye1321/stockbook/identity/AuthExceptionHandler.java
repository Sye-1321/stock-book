package io.github.sye1321.stockbook.identity;

import io.github.sye1321.stockbook.identity.PasswordChangeService.InvalidCurrentPasswordException;
import io.github.sye1321.stockbook.identity.PasswordChangeService.InvalidPasswordChangeException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AuthExceptionHandler {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> unreadableRequest(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse("invalid_request", "The request body is invalid"));
    }

    @ExceptionHandler(InvalidCurrentPasswordException.class)
    ResponseEntity<ErrorResponse> invalidCurrentPassword(InvalidCurrentPasswordException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("invalid_current_password", exception.getMessage()));
    }

    @ExceptionHandler(InvalidPasswordChangeException.class)
    ResponseEntity<ErrorResponse> invalidPasswordChange(InvalidPasswordChangeException exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse("invalid_request", exception.getMessage()));
    }

    public record ErrorResponse(String code, String message) {}
}
