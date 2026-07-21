package com.weepwood.cruddemo;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
class ApiController {
    private final MetadataService metadataService;
    private final GenericCrudService crudService;

    ApiController(MetadataService metadataService, GenericCrudService crudService) {
        this.metadataService = metadataService;
        this.crudService = crudService;
    }

    @GetMapping("/meta/schemas")
    List<SchemaInfo> schemas() {
        return metadataService.listSchemas();
    }

    @GetMapping("/meta/{schema}/tables")
    List<TableInfo> tables(@PathVariable String schema) {
        return metadataService.listTables(schema);
    }

    @GetMapping("/meta/{schema}/{table}")
    TableMetadata metadata(@PathVariable String schema, @PathVariable String table) {
        return metadataService.metadata(schema, table);
    }

    @GetMapping("/data/{schema}/{table}")
    PageResponse rows(
            @PathVariable String schema,
            @PathVariable String table,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "asc") String direction
    ) {
        return crudService.list(schema, table, page, size, q, sort, direction);
    }

    @PostMapping("/data/{schema}/{table}")
    Map<String, Object> create(
            @PathVariable String schema,
            @PathVariable String table,
            @RequestBody MutationRequest body
    ) {
        return crudService.create(schema, table, body);
    }

    @PutMapping("/data/{schema}/{table}")
    Map<String, Object> update(
            @PathVariable String schema,
            @PathVariable String table,
            @RequestBody MutationRequest body
    ) {
        return crudService.update(schema, table, body);
    }

    @DeleteMapping("/data/{schema}/{table}")
    Map<String, Object> delete(
            @PathVariable String schema,
            @PathVariable String table,
            @RequestBody MutationRequest body
    ) {
        return crudService.delete(schema, table, body);
    }
}

class ApiException extends RuntimeException {
    private final HttpStatus status;

    ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    HttpStatus status() {
        return status;
    }
}

@RestControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> api(ApiException exception) {
        return response(exception.status().value(), exception.getMessage());
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Map<String, Object>> database(DataAccessException exception) {
        var cause = exception.getMostSpecificCause();
        return response(400, cause == null ? "Database operation failed" : cause.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> argument(IllegalArgumentException exception) {
        return response(400, exception.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unexpected(Exception exception) {
        return response(500, "Unexpected server error");
    }

    private ResponseEntity<Map<String, Object>> response(int status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
