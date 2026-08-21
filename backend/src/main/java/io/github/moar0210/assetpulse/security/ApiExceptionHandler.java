package io.github.moar0210.assetpulse.security;

import io.github.moar0210.assetpulse.alerts.AlertNotFoundException;
import io.github.moar0210.assetpulse.alerts.AlertStateConflictException;
import io.github.moar0210.assetpulse.alerts.InvalidAlertQueryException;
import io.github.moar0210.assetpulse.assets.AssetNotFoundException;
import io.github.moar0210.assetpulse.identity.AlreadyAuthenticatedException;
import io.github.moar0210.assetpulse.identity.AuthenticationFailedException;
import io.github.moar0210.assetpulse.identity.AuthenticationUnavailableException;
import io.github.moar0210.assetpulse.telemetry.InvalidSensorReferenceException;
import io.github.moar0210.assetpulse.telemetry.InvalidTelemetryRangeException;
import io.github.moar0210.assetpulse.telemetry.SensorNotFoundException;
import io.github.moar0210.assetpulse.telemetry.TelemetryIdempotencyConflictException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private final ApiProblemWriter problemWriter;

    public ApiExceptionHandler(ApiProblemWriter problemWriter) {
        this.problemWriter = problemWriter;
    }

    @ExceptionHandler(AuthenticationFailedException.class)
    ResponseEntity<ProblemDetail> authenticationFailed(HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.UNAUTHORIZED,
                "AUTHENTICATION_FAILED",
                "Authentication failed",
                "The email or password is incorrect.");
    }

    @ExceptionHandler(AlreadyAuthenticatedException.class)
    ResponseEntity<ProblemDetail> alreadyAuthenticated(HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.CONFLICT,
                "SESSION_ALREADY_AUTHENTICATED",
                "Session already authenticated",
                "Sign out before signing in with another account.");
    }

    @ExceptionHandler(AuthenticationUnavailableException.class)
    ResponseEntity<ProblemDetail> authenticationUnavailable(HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.SERVICE_UNAVAILABLE,
                "AUTHENTICATION_UNAVAILABLE",
                "Authentication unavailable",
                "Authentication is temporarily unavailable. Try again later.");
    }

    @ExceptionHandler(AssetNotFoundException.class)
    ResponseEntity<ProblemDetail> assetNotFound(HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.NOT_FOUND,
                "ASSET_NOT_FOUND",
                "Asset not found",
                "The requested asset does not exist or is not accessible.");
    }

    @ExceptionHandler(AlertNotFoundException.class)
    ResponseEntity<ProblemDetail> alertNotFound(HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.NOT_FOUND,
                "ALERT_NOT_FOUND",
                "Alert not found",
                "The requested alert does not exist or is not accessible.");
    }

    @ExceptionHandler(AlertStateConflictException.class)
    ResponseEntity<ProblemDetail> alertStateConflict(HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.CONFLICT,
                "ALERT_STATE_CONFLICT",
                "Alert state conflict",
                "The alert cannot transition from its current state.");
    }

    @ExceptionHandler(InvalidAlertQueryException.class)
    ResponseEntity<ProblemDetail> invalidAlertQuery(HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.BAD_REQUEST,
                "INVALID_ALERT_QUERY",
                "Invalid alert query",
                "Provide a valid alert result limit from 1 to 100.");
    }

    @ExceptionHandler(SensorNotFoundException.class)
    ResponseEntity<ProblemDetail> sensorNotFound(HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.NOT_FOUND,
                "SENSOR_NOT_FOUND",
                "Sensor not found",
                "The requested sensor does not exist or is not accessible.");
    }

    @ExceptionHandler(InvalidTelemetryRangeException.class)
    ResponseEntity<ProblemDetail> invalidTelemetryRange(HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.BAD_REQUEST,
                "INVALID_TELEMETRY_RANGE",
                "Invalid telemetry range",
                "Provide a valid telemetry time range and result limit.");
    }

    @ExceptionHandler(InvalidSensorReferenceException.class)
    ResponseEntity<ProblemDetail> invalidTelemetrySensor(HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.BAD_REQUEST,
                "INVALID_TELEMETRY_SENSOR",
                "Invalid telemetry sensor",
                "One or more sensors do not exist or are not accessible.");
    }

    @ExceptionHandler(TelemetryIdempotencyConflictException.class)
    ResponseEntity<ProblemDetail> telemetryIdempotencyConflict(HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.CONFLICT,
                "IDEMPOTENCY_KEY_REUSED",
                "Idempotency key already used",
                "The idempotency key is already associated with another request.");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> validationFailed(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        ProblemDetail problem =
                problemWriter.create(
                        request,
                        HttpStatus.BAD_REQUEST.value(),
                        "INVALID_REQUEST",
                        "Invalid request",
                        "One or more request fields are invalid.");
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(fieldError.getField(), "invalid");
        }
        problem.setProperty("fieldErrors", fieldErrors);
        return ResponseEntity.badRequest()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> unreadableRequest(HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST",
                "Invalid request",
                "The request body is not valid JSON for this operation.");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> invalidPathParameter(HttpServletRequest request) {
        return problem(
                request,
                HttpStatus.BAD_REQUEST,
                "INVALID_PATH_PARAMETER",
                "Invalid path parameter",
                "One or more path parameters are invalid.");
    }

    private ResponseEntity<ProblemDetail> problem(
            HttpServletRequest request,
            HttpStatus status,
            String code,
            String title,
            String detail) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problemWriter.create(request, status.value(), code, title, detail));
    }
}
