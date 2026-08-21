package com.apurva.onboarding.api;

import com.apurva.onboarding.service.AutomationUnavailableException;
import com.apurva.onboarding.service.InvalidDocumentException;
import com.apurva.onboarding.service.InvalidSubmissionException;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiErrorResponse handleBeanValidation(MethodArgumentNotValidException exception) {
        List<FieldErrorDetail> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldErrorDetail(error.getField(), error.getDefaultMessage()))
                .toList();
        return ApiErrorResponse.of("VALIDATION_ERROR", "Please correct the highlighted fields.", errors);
    }

    @ExceptionHandler(InvalidSubmissionException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiErrorResponse handleInvalidSubmission(InvalidSubmissionException exception) {
        return ApiErrorResponse.of(
                "VALIDATION_ERROR",
                "Please correct the highlighted fields.",
                List.of(new FieldErrorDetail(exception.getField(), exception.getMessage()))
        );
    }

    @ExceptionHandler(InvalidDocumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiErrorResponse handleInvalidDocument(InvalidDocumentException exception) {
        return ApiErrorResponse.of(
                "VALIDATION_ERROR",
                "Please correct the highlighted fields.",
                List.of(new FieldErrorDetail("document", exception.getMessage()))
        );
    }

    @ExceptionHandler(AutomationUnavailableException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    ApiErrorResponse handleAutomationUnavailable() {
        return ApiErrorResponse.of(
                "AUTOMATION_UNAVAILABLE",
                "We could not review the document right now. Please try again shortly.",
                List.of()
        );
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ApiErrorResponse handleMaximumUploadSize() {
        return ApiErrorResponse.of(
                "VALIDATION_ERROR",
                "Please correct the highlighted fields.",
                List.of(new FieldErrorDetail("document", "The document must be 5 MB or smaller."))
        );
    }
}
