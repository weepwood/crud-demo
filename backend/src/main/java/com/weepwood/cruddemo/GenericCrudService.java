package com.weepwood.cruddemo;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
class GenericCrudService {
    private final JdbcTemplate jdbc;
    private final MetadataService metadataService;
    private final CrudProperties properties;
    private final ObjectMapper objectMapper;

    GenericCrudService(
            JdbcTemplate jdbc,
            MetadataService metadataService,
            CrudProperties properties,
            ObjectMapper objectMapper
    ) {
        this.jdbc = jdbc;
        this.metadataService = metadataService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    PageResponse list(
            String schema,
            String table,
            int page,
            int requestedSize,
            String query,
            String sort,
            String direction
    ) {
        var metadata = metadataService.metadata(schema, table);
        int safePage = Math.max(0, page);
        int size = Math.min(Math.max(1, requestedSize), properties.getMaxPageSize());
        List<Object> whereParams = new ArrayList<>();
        String where = searchClause(metadata, query, whereParams);
        String order = orderBy(metadata, sort, direction);
        String target = SqlSupport.qualifiedName(schema, table);

        var params = new ArrayList<>(whereParams);
        params.add(size);
        params.add((long) safePage * size);

        var rows = jdbc.query(
                "SELECT *, ctid::text AS \"__rowId\" FROM " + target + where + order + " LIMIT ? OFFSET ?",
                new ColumnMapRowMapper(),
                params.toArray()
        );
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + target + where,
                Long.class,
                whereParams.toArray()
        );
        return new PageResponse(rows, safePage, size, total == null ? 0 : total);
    }

    @Transactional
    Map<String, Object> create(String schema, String table, MutationRequest request) {
        var metadata = metadataService.metadata(schema, table);
        requireWritable(metadata.canInsert(), "INSERT is not allowed");

        var columns = metadata.columnMap();
        var values = validatedValues(request.values(), columns, false);
        String target = SqlSupport.qualifiedName(schema, table);

        if (values.isEmpty()) {
            return jdbc.queryForObject(
                    "INSERT INTO " + target + " DEFAULT VALUES RETURNING *, ctid::text AS \"__rowId\"",
                    new ColumnMapRowMapper()
            );
        }

        String names = values.keySet().stream()
                .map(SqlSupport::quoteIdentifier)
                .collect(Collectors.joining(", "));
        String placeholders = values.keySet().stream()
                .map(name -> SqlSupport.typedPlaceholder(columns.get(name)))
                .collect(Collectors.joining(", "));

        return jdbc.queryForObject(
                "INSERT INTO " + target + " (" + names + ") VALUES (" + placeholders + ") "
                        + "RETURNING *, ctid::text AS \"__rowId\"",
                new ColumnMapRowMapper(),
                normalized(values, columns).toArray()
        );
    }

    @Transactional
    Map<String, Object> update(String schema, String table, MutationRequest request) {
        var metadata = metadataService.metadata(schema, table);
        requireWritable(metadata.canUpdate(), "UPDATE is not allowed");

        var columns = metadata.columnMap();
        var values = validatedValues(request.values(), columns, true);
        if (values.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "No editable values were provided");
        }

        var params = normalized(values, columns);
        String assignments = values.keySet().stream()
                .map(name -> SqlSupport.quoteIdentifier(name) + " = " + SqlSupport.typedPlaceholder(columns.get(name)))
                .collect(Collectors.joining(", "));
        String where = identityClause(metadata, request, params);

        var rows = jdbc.query(
                "UPDATE " + SqlSupport.qualifiedName(schema, table)
                        + " SET " + assignments + where
                        + " RETURNING *, ctid::text AS \"__rowId\"",
                new ColumnMapRowMapper(),
                params.toArray()
        );
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "The row no longer exists");
        }
        return rows.getFirst();
    }

    @Transactional
    Map<String, Object> delete(String schema, String table, MutationRequest request) {
        var metadata = metadataService.metadata(schema, table);
        requireWritable(metadata.canDelete(), "DELETE is not allowed");

        List<Object> params = new ArrayList<>();
        String where = identityClause(metadata, request, params);
        var rows = jdbc.query(
                "DELETE FROM " + SqlSupport.qualifiedName(schema, table) + where
                        + " RETURNING *, ctid::text AS \"__rowId\"",
                new ColumnMapRowMapper(),
                params.toArray()
        );
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "The row no longer exists");
        }
        return rows.getFirst();
    }

    private Map<String, Object> validatedValues(
            Map<String, Object> raw,
            Map<String, ColumnInfo> columns,
            boolean update
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((name, value) -> {
            var column = columns.get(name);
            if (column == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown column: " + name);
            }
            if (!column.editable()) {
                if (update) {
                    return;
                }
                throw new ApiException(HttpStatus.BAD_REQUEST, "Column is generated or identity: " + name);
            }
            result.put(name, value);
        });
        return result;
    }

    private List<Object> normalized(Map<String, Object> values, Map<String, ColumnInfo> columns) {
        List<Object> params = new ArrayList<>();
        values.forEach((name, value) -> params.add(normalize(columns.get(name), value)));
        return params;
    }

    private Object normalize(ColumnInfo column, Object value) {
        if (value == null) {
            return null;
        }
        if (column.jsonType() && !(value instanceof String)) {
            try {
                return objectMapper.writeValueAsString(value);
            } catch (Exception exception) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid JSON for " + column.name());
            }
        }
        return value;
    }

    private String identityClause(TableMetadata metadata, MutationRequest request, List<Object> params) {
        if (metadata.hasPrimaryKey()) {
            var columns = metadata.columnMap();
            var missing = metadata.primaryKeys().stream()
                    .filter(key -> !request.keys().containsKey(key))
                    .toList();
            if (!missing.isEmpty()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Missing primary key values: " + missing);
            }

            return " WHERE " + metadata.primaryKeys().stream()
                    .map(key -> {
                        params.add(normalize(columns.get(key), request.keys().get(key)));
                        return SqlSupport.quoteIdentifier(key)
                                + " IS NOT DISTINCT FROM "
                                + SqlSupport.typedPlaceholder(columns.get(key));
                    })
                    .collect(Collectors.joining(" AND "));
        }

        if (request.rowId() == null || request.rowId().isBlank()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "rowId is required for a table without a primary key"
            );
        }
        params.add(request.rowId());
        return " WHERE ctid::text = ?";
    }

    private String searchClause(TableMetadata metadata, String query, List<Object> params) {
        if (query == null || query.isBlank()) {
            return "";
        }
        String value = "%" + query.trim() + "%";
        return " WHERE (" + metadata.columns().stream()
                .map(column -> {
                    params.add(value);
                    return "CAST(" + SqlSupport.quoteIdentifier(column.name()) + " AS TEXT) ILIKE ?";
                })
                .collect(Collectors.joining(" OR ")) + ")";
    }

    private String orderBy(TableMetadata metadata, String sort, String direction) {
        String column = sort;
        if (column == null || !metadata.columnMap().containsKey(column)) {
            column = metadata.hasPrimaryKey()
                    ? metadata.primaryKeys().getFirst()
                    : metadata.columns().getFirst().name();
        }
        return " ORDER BY " + SqlSupport.quoteIdentifier(column)
                + ("desc".equalsIgnoreCase(direction) ? " DESC" : " ASC");
    }

    private void requireWritable(boolean allowed, String message) {
        if (properties.isReadOnly() || !allowed) {
            throw new ApiException(HttpStatus.FORBIDDEN, message);
        }
    }
}
