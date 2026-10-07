package com.preclinic.backend.common;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import jakarta.validation.ConstraintViolationException;

/**
 * One error format for the whole API: RFC 7807 {@link ProblemDetail} plus a stable {@code code}
 * (and {@code errors} for field validation failures). Extends Spring's standard handler so ordinary
 * MVC errors (404, 405, missing parameters ...) keep their correct status instead of becoming 500s.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	public record FieldError(String field, String message) {
	}

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ProblemDetail> handleApi(ApiException ex) {
		return problem(ex.getStatus(), ex.getCode(), ex.getMessage());
	}

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<FieldError> errors = ex.getBindingResult().getFieldErrors().stream()
				.map(e -> new FieldError(e.getField(), e.getDefaultMessage())).toList();
		return ResponseEntity.badRequest().body(validationProblem(errors));
	}

	@Override
	protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
				"The request body is missing or malformed.");
		body.setProperty("code", "MALFORMED_REQUEST");
		return ResponseEntity.badRequest().body(body);
	}

	@ExceptionHandler(ConstraintViolationException.class)
	ResponseEntity<ProblemDetail> handleInvalidParams(ConstraintViolationException ex) {
		List<FieldError> errors = ex.getConstraintViolations().stream()
				.map(v -> new FieldError(v.getPropertyPath().toString(), v.getMessage())).toList();
		return ResponseEntity.badRequest().body(validationProblem(errors));
	}

	@ExceptionHandler(DataIntegrityViolationException.class)
	ResponseEntity<ProblemDetail> handleIntegrity(DataIntegrityViolationException ex) {
		return ConstraintErrors.find(ex)
				.map(m -> problem(m.status(), m.code(), m.message()))
				.orElseGet(() -> {
					log.warn("Unmapped data integrity violation", ex);
					return problem(HttpStatus.CONFLICT, "DATA_CONFLICT",
							"The request conflicts with existing data.");
				});
	}

	/** Method-security denials raised inside controllers/services land here, not in the filter chain. */
	@ExceptionHandler(AccessDeniedException.class)
	ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
		return problem(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to do that.");
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
		log.error("Unhandled exception", ex);
		return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Something went wrong on the server.");
	}

	private static ProblemDetail validationProblem(List<FieldError> errors) {
		ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed.");
		body.setProperty("code", "VALIDATION_FAILED");
		body.setProperty("errors", errors);
		return body;
	}

	private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
		ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
		body.setProperty("code", code);
		return ResponseEntity.status(status).body(body);
	}
}
