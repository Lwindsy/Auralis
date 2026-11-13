package io.auralisy.bootstrap;

import io.auralisy.catalog.api.CatalogFailure;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = CatalogHttpController.class)
public class CatalogHttpAdvice {
    private final HttpErrors errors;

    public CatalogHttpAdvice(HttpErrors errors) {
        this.errors = errors;
    }

    @ExceptionHandler(CatalogFailure.class)
    public void failure(
            CatalogFailure failure, HttpServletRequest request, HttpServletResponse response)
            throws java.io.IOException {
        errors.write(request, response, failure.status(), failure.getMessage());
    }

    @ExceptionHandler({
        MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class
    })
    public void invalid(Exception failure, HttpServletRequest request, HttpServletResponse response)
            throws java.io.IOException {
        errors.write(request, response, 400, "INVALID_PAYLOAD");
    }

    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public void unavailable(
            Exception failure, HttpServletRequest request, HttpServletResponse response)
            throws java.io.IOException {
        errors.write(request, response, 503, "CATALOG_UNAVAILABLE");
    }
}
