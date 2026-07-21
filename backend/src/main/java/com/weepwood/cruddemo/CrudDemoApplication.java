package com.weepwood.cruddemo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.ColumnMapRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@SpringBootApplication
@EnableConfigurationProperties(CrudProperties.class)
public class CrudDemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(CrudDemoApplication.class, args);
    }
}

@ConfigurationProperties(prefix = "app.crud")
class CrudProperties {
    private List<String> allowedSchemas = new ArrayList<>();
    private int maxPageSize = 200;
    private boolean readOnly;
    private String username = "admin";
    private String password = "change-me-now";
    private List<String> allowedOrigins = new ArrayList<>(List.of("http://localhost:5173", "http://localhost:8080"));

    public List<String> getAllowedSchemas() { return allowedSchemas; }
    public void setAllowedSchemas(List<String> value) { allowedSchemas = value == null ? new ArrayList<>() : value; }
    public int getMaxPageSize() { return maxPageSize; }
    public void setMaxPageSize(int value) { maxPageSize = Math.max(1, value); }
    public boolean isReadOnly() { return readOnly; }
    public void setReadOnly(boolean value) { readOnly = value; }
    public String getUsername() { return username; }
    public void setUsername(String value) { username = value; }
    public String getPassword() { return password; }
    public void setPassword(String value) { password = value; }
    public List<String> getAllowedOrigins() { return allowedOrigins; }
    public void setAllowedOrigins(List<String> value) { allowedOrigins = value == null ? new ArrayList<>() : value; }
}

@Configuration
class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults())
                .build();
    }

    @Bean PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(CrudProperties properties, PasswordEncoder encoder) {
        return new InMemoryUserDetailsManager(User.withUsername(properties.getUsername())
                .password(encoder.encode(properties.getPassword())).roles("ADMIN").build());
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(CrudProperties properties) {
        var configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.getAllowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        configuration.setExposedHeaders(List.of("WWW-Authenticate"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}

record SchemaInfo(String name) {}
record TableInfo(String schema, String name, long estimatedRows, boolean hasPrimaryKey,
                 boolean canInsert, boolean canUpdate, boolean canDelete) {}
record ColumnInfo(String name, String formattedType, String udtName, String typeCategory,
                  boolean nullable, String defaultValue, boolean identity, boolean generated,
                  boolean primaryKey, int ordinalPosition) {
    boolean editable() { return !identity && !generated; }
    boolean jsonType() { return "json".equals(udtName) || "jsonb".equals(udtName); }
}
record TableMetadata(String schema, String table, List<ColumnInfo> columns, List<String> primaryKeys,
                     boolean canInsert, boolean canUpdate, boolean canDelete) {
    Map<String, ColumnInfo> columnMap() {
        return columns.stream().collect(Collectors.toMap(ColumnInfo::name, Function.identity(),
                (left, right) -> left, LinkedHashMap::new));
    }
    boolean hasPrimaryKey() { return !primaryKeys.isEmpty(); }
}
record PageResponse(List<Map<String, Object>> rows, int page, int size, long total) {}
record MutationRequest(Map<String, Object> values, Map<String, Object> keys, String rowId) {
    MutationRequest {
        values = values == null ? Map.of() : values;
        keys = keys == null ? Map.of() : keys;
    }
}

final class SqlSupport {
    private SqlSupport() {}
    static String quoteIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) throw new IllegalArgumentException("SQL identifier must not be blank");
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
    static String qualifiedName(String schema, String table) {
        return quoteIdentifier(schema) + "." + quoteIdentifier(table);
    }
    static String typedPlaceholder(ColumnInfo column) {
        return "CAST(? AS " + column.formattedType() + ")";
    }
}

@Service
class MetadataService {
    private final JdbcTemplate jdbc;
    private final CrudProperties properties;
    MetadataService(JdbcTemplate jdbc, CrudProperties properties) { this.jdbc = jdbc; this.properties = properties; }

    List<SchemaInfo> listSchemas() {
        String sql = """
                SELECT n.nspname FROM pg_catalog.pg_namespace n
                WHERE n.nspname <> 'information_schema' AND n.nspname NOT LIKE 'pg_%'
                ORDER BY n.nspname
                """;
        return jdbc.query(sql, (rs, row) -> new SchemaInfo(rs.getString("nspname"))).stream()
                .filter(schema -> isAllowed(schema.name())).toList();
    }

    List<TableInfo> listTables(String schema) {
        requireAllowed(schema);
        String sql = """
                SELECT c.relname, GREATEST(c.reltuples::bigint, 0) AS estimated_rows,
                       EXISTS (SELECT 1 FROM pg_catalog.pg_index i WHERE i.indrelid=c.oid AND i.indisprimary) AS has_primary_key,
                       has_table_privilege(c.oid, 'INSERT') AS can_insert,
                       has_table_privilege(c.oid, 'UPDATE') AS can_update,
                       has_table_privilege(c.oid, 'DELETE') AS can_delete
                FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname=? AND c.relkind IN ('r','p') AND has_table_privilege(c.oid, 'SELECT')
                ORDER BY c.relname
                """;
        return jdbc.query(sql, (rs, row) -> new TableInfo(schema, rs.getString("relname"),
                rs.getLong("estimated_rows"), rs.getBoolean("has_primary_key"),
                rs.getBoolean("can_insert") && !properties.isReadOnly(),
                rs.getBoolean("can_update") && !properties.isReadOnly(),
                rs.getBoolean("can_delete") && !properties.isReadOnly()), schema);
    }

    TableMetadata metadata(String schema, String table) {
        requireAllowed(schema);
        var info = listTables(schema).stream().filter(item -> item.name().equals(table)).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Table not found or not accessible"));
        String sql = """
                SELECT a.attname, pg_catalog.format_type(a.atttypid,a.atttypmod) AS formatted_type,
                       t.typname AS udt_name, t.typcategory::text AS type_category,
                       NOT a.attnotnull AS nullable, pg_catalog.pg_get_expr(ad.adbin,ad.adrelid) AS default_value,
                       a.attidentity<>'' AS identity_column, a.attgenerated<>'' AS generated_column,
                       EXISTS (SELECT 1 FROM pg_catalog.pg_index i WHERE i.indrelid=c.oid AND i.indisprimary AND a.attnum=ANY(i.indkey)) AS primary_key,
                       a.attnum AS ordinal_position
                FROM pg_catalog.pg_attribute a
                JOIN pg_catalog.pg_class c ON c.oid=a.attrelid
                JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
                JOIN pg_catalog.pg_type t ON t.oid=a.atttypid
                LEFT JOIN pg_catalog.pg_attrdef ad ON ad.adrelid=a.attrelid AND ad.adnum=a.attnum
                WHERE n.nspname=? AND c.relname=? AND c.relkind IN ('r','p') AND a.attnum>0 AND NOT a.attisdropped
                ORDER BY a.attnum
                """;
        List<ColumnInfo> columns = jdbc.query(sql, (rs, row) -> new ColumnInfo(rs.getString("attname"),
                rs.getString("formatted_type"), rs.getString("udt_name"), rs.getString("type_category"),
                rs.getBoolean("nullable"), rs.getString("default_value"), rs.getBoolean("identity_column"),
                rs.getBoolean("generated_column"), rs.getBoolean("primary_key"), rs.getInt("ordinal_position")), schema, table);
        if (columns.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Table has no accessible columns");
        var keys = columns.stream().filter(ColumnInfo::primaryKey).map(ColumnInfo::name).toList();
        return new TableMetadata(schema, table, columns, keys, info.canInsert(), info.canUpdate(), info.canDelete());
    }

    private boolean isAllowed(String schema) {
        return properties.getAllowedSchemas().isEmpty() || properties.getAllowedSchemas().contains(schema);
    }
    private void requireAllowed(String schema) {
        if (schema == null || schema.isBlank() || !isAllowed(schema))
            throw new ApiException(HttpStatus.FORBIDDEN, "Schema is not allowed");
    }
}

@Service
class GenericCrudService {
    private final JdbcTemplate jdbc;
    private final MetadataService metadataService;
    private final CrudProperties properties;
    private final ObjectMapper objectMapper;
    GenericCrudService(JdbcTemplate jdbc, MetadataService metadataService, CrudProperties properties, ObjectMapper objectMapper) {
        this.jdbc = jdbc; this.metadataService = metadataService; this.properties = properties; this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    PageResponse list(String schema, String table, int page, int requestedSize, String query, String sort, String direction) {
        var metadata = metadataService.metadata(schema, table);
        int safePage = Math.max(0, page);
        int size = Math.min(Math.max(1, requestedSize), properties.getMaxPageSize());
        List<Object> whereParams = new ArrayList<>();
        String where = searchClause(metadata, query, whereParams);
        String order = orderBy(metadata, sort, direction);
        String target = SqlSupport.qualifiedName(schema, table);
        var params = new ArrayList<>(whereParams);
        params.add(size); params.add((long) safePage * size);
        var rows = jdbc.query("SELECT *, ctid::text AS \"__rowId\" FROM " + target + where + order + " LIMIT ? OFFSET ?",
                new ColumnMapRowMapper(), params.toArray());
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM " + target + where, Long.class, whereParams.toArray());
        return new PageResponse(rows, safePage, size, total == null ? 0 : total);
    }

    @Transactional
    Map<String, Object> create(String schema, String table, MutationRequest request) {
        var metadata = metadataService.metadata(schema, table); requireWritable(metadata.canInsert(), "INSERT is not allowed");
        var columns = metadata.columnMap(); var values = validatedValues(request.values(), columns, false);
        String target = SqlSupport.qualifiedName(schema, table);
        if (values.isEmpty()) return jdbc.queryForObject("INSERT INTO " + target + " DEFAULT VALUES RETURNING *, ctid::text AS \"__rowId\"", new ColumnMapRowMapper());
        String names = values.keySet().stream().map(SqlSupport::quoteIdentifier).collect(Collectors.joining(", "));
        String placeholders = values.keySet().stream().map(name -> SqlSupport.typedPlaceholder(columns.get(name))).collect(Collectors.joining(", "));
        return jdbc.queryForObject("INSERT INTO " + target + " (" + names + ") VALUES (" + placeholders + ") RETURNING *, ctid::text AS \"__rowId\"",
                new ColumnMapRowMapper(), normalized(values, columns).toArray());
    }

    @Transactional
    Map<String, Object> update(String schema, String table, MutationRequest request) {
        var metadata = metadataService.metadata(schema, table); requireWritable(metadata.canUpdate(), "UPDATE is not allowed");
        var columns = metadata.columnMap(); var values = validatedValues(request.values(), columns, true);
        if (values.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "No editable values were provided");
        var params = normalized(values, columns);
        String set = values.keySet().stream().map(name -> SqlSupport.quoteIdentifier(name) + " = " + SqlSupport.typedPlaceholder(columns.get(name))).collect(Collectors.joining(", "));
        String where = identityClause(metadata, request, params);
        var rows = jdbc.query("UPDATE " + SqlSupport.qualifiedName(schema, table) + " SET " + set + where + " RETURNING *, ctid::text AS \"__rowId\"",
                new ColumnMapRowMapper(), params.toArray());
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "The row no longer exists");
        return rows.getFirst();
    }

    @Transactional
    Map<String, Object> delete(String schema, String table, MutationRequest request) {
        var metadata = metadataService.metadata(schema, table); requireWritable(metadata.canDelete(), "DELETE is not allowed");
        List<Object> params = new ArrayList<>(); String where = identityClause(metadata, request, params);
        var rows = jdbc.query("DELETE FROM " + SqlSupport.qualifiedName(schema, table) + where + " RETURNING *, ctid::text AS \"__rowId\"",
                new ColumnMapRowMapper(), params.toArray());
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "The row no longer exists");
        return rows.getFirst();
    }

    private Map<String,Object> validatedValues(Map<String,Object> raw, Map<String,ColumnInfo> columns, boolean update) {
        Map<String,Object> result = new LinkedHashMap<>();
        raw.forEach((name, value) -> {
            var column = columns.get(name);
            if (column == null) throw new ApiException(HttpStatus.BAD_REQUEST, "Unknown column: " + name);
            if (!column.editable()) {
                if (update) return;
                throw new ApiException(HttpStatus.BAD_REQUEST, "Column is generated or identity: " + name);
            }
            result.put(name, value);
        });
        return result;
    }
    private List<Object> normalized(Map<String,Object> values, Map<String,ColumnInfo> columns) {
        List<Object> params = new ArrayList<>(); values.forEach((name,value) -> params.add(normalize(columns.get(name), value))); return params;
    }
    private Object normalize(ColumnInfo column, Object value) {
        if (value == null) return null;
        if (column.jsonType() && !(value instanceof String)) {
            try { return objectMapper.writeValueAsString(value); }
            catch (JsonProcessingException e) { throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid JSON for " + column.name()); }
        }
        return value;
    }
    private String identityClause(TableMetadata metadata, MutationRequest request, List<Object> params) {
        if (metadata.hasPrimaryKey()) {
            var columns = metadata.columnMap();
            var missing = metadata.primaryKeys().stream().filter(key -> !request.keys().containsKey(key)).toList();
            if (!missing.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "Missing primary key values: " + missing);
            return " WHERE " + metadata.primaryKeys().stream().map(key -> {
                params.add(normalize(columns.get(key), request.keys().get(key)));
                return SqlSupport.quoteIdentifier(key) + " IS NOT DISTINCT FROM " + SqlSupport.typedPlaceholder(columns.get(key));
            }).collect(Collectors.joining(" AND "));
        }
        if (request.rowId() == null || request.rowId().isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "rowId is required for a table without a primary key");
        params.add(request.rowId()); return " WHERE ctid::text = ?";
    }
    private String searchClause(TableMetadata metadata, String query, List<Object> params) {
        if (query == null || query.isBlank()) return "";
        String value = "%" + query.trim() + "%";
        return " WHERE (" + metadata.columns().stream().map(column -> { params.add(value); return "CAST(" + SqlSupport.quoteIdentifier(column.name()) + " AS TEXT) ILIKE ?"; }).collect(Collectors.joining(" OR ")) + ")";
    }
    private String orderBy(TableMetadata metadata, String sort, String direction) {
        String column = sort;
        if (column == null || !metadata.columnMap().containsKey(column)) column = metadata.hasPrimaryKey() ? metadata.primaryKeys().getFirst() : metadata.columns().getFirst().name();
        return " ORDER BY " + SqlSupport.quoteIdentifier(column) + ("desc".equalsIgnoreCase(direction) ? " DESC" : " ASC");
    }
    private void requireWritable(boolean allowed, String message) {
        if (properties.isReadOnly() || !allowed) throw new ApiException(HttpStatus.FORBIDDEN, message);
    }
}

@RestController
@RequestMapping("/api")
class ApiController {
    private final MetadataService metadata;
    private final GenericCrudService crud;
    ApiController(MetadataService metadata, GenericCrudService crud) { this.metadata = metadata; this.crud = crud; }

    @GetMapping("/meta/schemas") List<SchemaInfo> schemas() { return metadata.listSchemas(); }
    @GetMapping("/meta/{schema}/tables") List<TableInfo> tables(@PathVariable String schema) { return metadata.listTables(schema); }
    @GetMapping("/meta/{schema}/{table}") TableMetadata metadata(@PathVariable String schema, @PathVariable String table) { return metadata.metadata(schema, table); }
    @GetMapping("/data/{schema}/{table}") PageResponse rows(@PathVariable String schema, @PathVariable String table,
        @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="50") int size,
        @RequestParam(required=false) String q, @RequestParam(required=false) String sort,
        @RequestParam(defaultValue="asc") String direction) { return crud.list(schema, table, page, size, q, sort, direction); }
    @PostMapping("/data/{schema}/{table}") Map<String,Object> create(@PathVariable String schema,@PathVariable String table,@RequestBody MutationRequest body) { return crud.create(schema,table,body); }
    @PutMapping("/data/{schema}/{table}") Map<String,Object> update(@PathVariable String schema,@PathVariable String table,@RequestBody MutationRequest body) { return crud.update(schema,table,body); }
    @DeleteMapping("/data/{schema}/{table}") Map<String,Object> delete(@PathVariable String schema,@PathVariable String table,@RequestBody MutationRequest body) { return crud.delete(schema,table,body); }
}

class ApiException extends RuntimeException {
    private final HttpStatus status;
    ApiException(HttpStatus status, String message) { super(message); this.status = status; }
    HttpStatus status() { return status; }
}

@RestControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String,Object>> api(ApiException e) { return response(e.status().value(), e.getMessage()); }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Map<String,Object>> database(DataAccessException e) {
        var cause = e.getMostSpecificCause(); return response(400, cause == null ? "Database operation failed" : cause.getMessage());
    }
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String,Object>> argument(IllegalArgumentException e) { return response(400, e.getMessage()); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String,Object>> unexpected(Exception e) { return response(500, "Unexpected server error"); }
    private ResponseEntity<Map<String,Object>> response(int status, String message) {
        Map<String,Object> body = new LinkedHashMap<>(); body.put("timestamp", Instant.now().toString()); body.put("status", status); body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
