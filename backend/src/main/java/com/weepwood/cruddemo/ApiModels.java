package com.weepwood.cruddemo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

record SchemaInfo(String name) {}

record TableInfo(
        String schema,
        String name,
        long estimatedRows,
        boolean hasPrimaryKey,
        boolean canInsert,
        boolean canUpdate,
        boolean canDelete
) {}

record ColumnInfo(
        String name,
        String formattedType,
        String udtName,
        String typeCategory,
        boolean nullable,
        String defaultValue,
        boolean identity,
        boolean generated,
        boolean primaryKey,
        int ordinalPosition
) {
    boolean editable() {
        return !identity && !generated;
    }

    boolean jsonType() {
        return "json".equals(udtName) || "jsonb".equals(udtName);
    }
}

record TableMetadata(
        String schema,
        String table,
        List<ColumnInfo> columns,
        List<String> primaryKeys,
        boolean canInsert,
        boolean canUpdate,
        boolean canDelete
) {
    Map<String, ColumnInfo> columnMap() {
        return columns.stream().collect(Collectors.toMap(
                ColumnInfo::name,
                Function.identity(),
                (left, right) -> left,
                LinkedHashMap::new
        ));
    }

    boolean hasPrimaryKey() {
        return !primaryKeys.isEmpty();
    }
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
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("SQL identifier must not be blank");
        }
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    static String qualifiedName(String schema, String table) {
        return quoteIdentifier(schema) + "." + quoteIdentifier(table);
    }

    static String typedPlaceholder(ColumnInfo column) {
        return "CAST(? AS " + column.formattedType() + ")";
    }
}
