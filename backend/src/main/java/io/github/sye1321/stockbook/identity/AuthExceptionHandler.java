package io.github.sye1321.stockbook.identity;

import io.github.sye1321.stockbook.identity.PasswordChangeService.InvalidCurrentPasswordException;
import io.github.sye1321.stockbook.identity.PasswordChangeService.InvalidPasswordChangeException;
import io.github.sye1321.stockbook.identity.staff.StaffAccountService.InvalidStaffAccountException;
import io.github.sye1321.stockbook.identity.staff.StaffAccountService.StaffAccountConflictException;
import io.github.sye1321.stockbook.identity.staff.StaffAccountService.StaffAccountNotFoundException;
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

    @ExceptionHandler(InvalidStaffAccountException.class)
    ResponseEntity<ErrorResponse> invalidStaffAccount(InvalidStaffAccountException exception) {
        return ResponseEntity.badRequest().body(new ErrorResponse("invalid_request", exception.getMessage()));
    }

    @ExceptionHandler(StaffAccountNotFoundException.class)
    ResponseEntity<ErrorResponse> staffAccountNotFound(StaffAccountNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("not_found", "Staff account was not found"));
    }

    @ExceptionHandler(StaffAccountConflictException.class)
    ResponseEntity<ErrorResponse> staffAccountConflict(StaffAccountConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("conflict", exception.getMessage()));
    }

    public record ErrorResponse(String code, String message) {}
}
