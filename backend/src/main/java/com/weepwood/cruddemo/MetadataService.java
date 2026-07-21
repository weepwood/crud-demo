package com.weepwood.cruddemo;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
class MetadataService {
    private final JdbcTemplate jdbc;
    private final CrudProperties properties;

    MetadataService(JdbcTemplate jdbc, CrudProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    List<SchemaInfo> listSchemas() {
        String sql = """
                SELECT n.nspname
                FROM pg_catalog.pg_namespace n
                WHERE n.nspname <> 'information_schema'
                  AND n.nspname NOT LIKE 'pg_%'
                ORDER BY n.nspname
                """;
        return jdbc.query(sql, (rs, row) -> new SchemaInfo(rs.getString("nspname")))
                .stream()
                .filter(schema -> isAllowed(schema.name()))
                .toList();
    }

    List<TableInfo> listTables(String schema) {
        requireAllowed(schema);
        String sql = """
                SELECT c.relname,
                       GREATEST(c.reltuples::bigint, 0) AS estimated_rows,
                       EXISTS (
                           SELECT 1
                           FROM pg_catalog.pg_index i
                           WHERE i.indrelid = c.oid AND i.indisprimary
                       ) AS has_primary_key,
                       has_table_privilege(c.oid, 'INSERT') AS can_insert,
                       has_table_privilege(c.oid, 'UPDATE') AS can_update,
                       has_table_privilege(c.oid, 'DELETE') AS can_delete
                FROM pg_catalog.pg_class c
                JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = ?
                  AND c.relkind IN ('r', 'p')
                  AND has_table_privilege(c.oid, 'SELECT')
                ORDER BY c.relname
                """;
        return jdbc.query(sql, (rs, row) -> new TableInfo(
                schema,
                rs.getString("relname"),
                rs.getLong("estimated_rows"),
                rs.getBoolean("has_primary_key"),
                rs.getBoolean("can_insert") && !properties.isReadOnly(),
                rs.getBoolean("can_update") && !properties.isReadOnly(),
                rs.getBoolean("can_delete") && !properties.isReadOnly()
        ), schema);
    }

    TableMetadata metadata(String schema, String table) {
        requireAllowed(schema);
        var tableInfo = listTables(schema).stream()
                .filter(item -> item.name().equals(table))
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Table not found or not accessible"));

        String sql = """
                SELECT a.attname,
                       pg_catalog.format_type(a.atttypid, a.atttypmod) AS formatted_type,
                       t.typname AS udt_name,
                       t.typcategory::text AS type_category,
                       NOT a.attnotnull AS nullable,
                       pg_catalog.pg_get_expr(ad.adbin, ad.adrelid) AS default_value,
                       a.attidentity <> '' AS identity_column,
                       a.attgenerated <> '' AS generated_column,
                       EXISTS (
                           SELECT 1
                           FROM pg_catalog.pg_index i
                           WHERE i.indrelid = c.oid
                             AND i.indisprimary
                             AND a.attnum = ANY(i.indkey)
                       ) AS primary_key,
                       a.attnum AS ordinal_position
                FROM pg_catalog.pg_attribute a
                JOIN pg_catalog.pg_class c ON c.oid = a.attrelid
                JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                JOIN pg_catalog.pg_type t ON t.oid = a.atttypid
                LEFT JOIN pg_catalog.pg_attrdef ad
                       ON ad.adrelid = a.attrelid AND ad.adnum = a.attnum
                WHERE n.nspname = ?
                  AND c.relname = ?
                  AND c.relkind IN ('r', 'p')
                  AND a.attnum > 0
                  AND NOT a.attisdropped
                ORDER BY a.attnum
                """;

        List<ColumnInfo> columns = jdbc.query(sql, (rs, row) -> new ColumnInfo(
                rs.getString("attname"),
                rs.getString("formatted_type"),
                rs.getString("udt_name"),
                rs.getString("type_category"),
                rs.getBoolean("nullable"),
                rs.getString("default_value"),
                rs.getBoolean("identity_column"),
                rs.getBoolean("generated_column"),
                rs.getBoolean("primary_key"),
                rs.getInt("ordinal_position")
        ), schema, table);

        if (columns.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Table has no accessible columns");
        }

        var primaryKeys = columns.stream()
                .filter(ColumnInfo::primaryKey)
                .map(ColumnInfo::name)
                .toList();
        return new TableMetadata(
                schema,
                table,
                columns,
                primaryKeys,
                tableInfo.canInsert(),
                tableInfo.canUpdate(),
                tableInfo.canDelete()
        );
    }

    private boolean isAllowed(String schema) {
        var allowed = properties.getAllowedSchemas().stream()
                .filter(value -> value != null && !value.isBlank())
                .toList();
        return allowed.isEmpty() || allowed.contains(schema);
    }

    private void requireAllowed(String schema) {
        if (schema == null || schema.isBlank() || !isAllowed(schema)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Schema is not allowed");
        }
    }
}
