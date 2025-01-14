/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.plan.logical.command.sys;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.common.Strings;
import org.elasticsearch.xpack.sql.analysis.index.EsIndex;
import org.elasticsearch.xpack.sql.expression.Attribute;
import org.elasticsearch.xpack.sql.expression.predicate.regex.LikePattern;
import org.elasticsearch.xpack.sql.plan.logical.command.Command;
import org.elasticsearch.xpack.sql.proto.Mode;
import org.elasticsearch.xpack.sql.session.Rows;
import org.elasticsearch.xpack.sql.session.SchemaRowSet;
import org.elasticsearch.xpack.sql.session.SqlSession;
import org.elasticsearch.xpack.sql.tree.Location;
import org.elasticsearch.xpack.sql.tree.NodeInfo;
import org.elasticsearch.xpack.sql.type.DataType;
import org.elasticsearch.xpack.sql.type.DataTypes;
import org.elasticsearch.xpack.sql.type.EsField;

import java.sql.DatabaseMetaData;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import static java.util.Arrays.asList;
import static org.elasticsearch.xpack.sql.type.DataType.INTEGER;
import static org.elasticsearch.xpack.sql.type.DataType.SHORT;

/**
 * System command designed to be used by JDBC / ODBC for column metadata, such as
 * {@link DatabaseMetaData#getColumns(String, String, String, String)}.
 */
public class SysColumns extends Command {

    private final String catalog;
    private final String index;
    private final LikePattern pattern;
    private final LikePattern columnPattern;

    public SysColumns(Location location, String catalog, String index, LikePattern pattern, LikePattern columnPattern) {
        super(location);
        this.catalog = catalog;
        this.index = index;
        this.pattern = pattern;
        this.columnPattern = columnPattern;
    }

    @Override
    protected NodeInfo<SysColumns> info() {
        return NodeInfo.create(this, SysColumns::new, catalog, index, pattern, columnPattern);
    }

    @Override
    public List<Attribute> output() {
        return output(false);
    }
    
    private List<Attribute> output(boolean odbcCompatible) {
        // https://github.com/elastic/elasticsearch/issues/35376
        // ODBC expects some fields as SHORT while JDBC as Integer
        // which causes conversion issues and CCE
        DataType clientBasedType = odbcCompatible ? SHORT : INTEGER;
        return asList(keyword("TABLE_CAT"),
                      keyword("TABLE_SCHEM"),
                      keyword("TABLE_NAME"),
                      keyword("COLUMN_NAME"),
                      field("DATA_TYPE", clientBasedType),
                      keyword("TYPE_NAME"),
                      field("COLUMN_SIZE", INTEGER),
                      field("BUFFER_LENGTH", INTEGER),
                      field("DECIMAL_DIGITS", clientBasedType),
                      field("NUM_PREC_RADIX", clientBasedType),
                      field("NULLABLE", clientBasedType),
                      keyword("REMARKS"),
                      keyword("COLUMN_DEF"),
                      field("SQL_DATA_TYPE", clientBasedType),
                      field("SQL_DATETIME_SUB", clientBasedType),
                      field("CHAR_OCTET_LENGTH", INTEGER),
                      field("ORDINAL_POSITION", INTEGER),
                      keyword("IS_NULLABLE"),
                      // JDBC specific
                      keyword("SCOPE_CATALOG"),
                      keyword("SCOPE_SCHEMA"),
                      keyword("SCOPE_TABLE"),
                      field("SOURCE_DATA_TYPE", SHORT),
                      keyword("IS_AUTOINCREMENT"),
                      keyword("IS_GENERATEDCOLUMN")
                      );
    }

    @Override
    public void execute(SqlSession session, ActionListener<SchemaRowSet> listener) {
        boolean isOdbcClient = session.configuration().mode() == Mode.ODBC;
        List<Attribute> output = output(isOdbcClient);
        String cluster = session.indexResolver().clusterName();

        // bail-out early if the catalog is present but differs
        if (Strings.hasText(catalog) && !cluster.equals(catalog)) {
            listener.onResponse(Rows.empty(output));
            return;
        }

        String idx = index != null ? index : (pattern != null ? pattern.asIndexNameWildcard() : "*");
        String regex = pattern != null ? pattern.asJavaRegex() : null;

        Pattern columnMatcher = columnPattern != null ? Pattern.compile(columnPattern.asJavaRegex()) : null;

        session.indexResolver().resolveAsSeparateMappings(idx, regex, ActionListener.wrap(esIndices -> {
            List<List<?>> rows = new ArrayList<>();
            for (EsIndex esIndex : esIndices) {
                fillInRows(cluster, esIndex.name(), esIndex.mapping(), null, rows, columnMatcher, isOdbcClient);
            }

            listener.onResponse(Rows.of(output, rows));
        }, listener::onFailure));
    }

    static void fillInRows(String clusterName, String indexName, Map<String, EsField> mapping, String prefix, List<List<?>> rows,
            Pattern columnMatcher, boolean isOdbcClient) {
        int pos = 0;
        for (Map.Entry<String, EsField> entry : mapping.entrySet()) {
            pos++; // JDBC is 1-based so we start with 1 here

            String name = entry.getKey();
            name = prefix != null ? prefix + "." + name : name;
            EsField field = entry.getValue();
            DataType type = field.getDataType();
            
            // skip the nested and object types only for ODBC
            // https://github.com/elastic/elasticsearch/issues/35376
            if (type.isPrimitive() || !isOdbcClient) {
                if (columnMatcher == null || columnMatcher.matcher(name).matches()) {
                    rows.add(asList(clusterName,
                            // schema is not supported
                            null,
                            indexName,
                            name,
                            odbcCompatible(type.sqlType.getVendorTypeNumber(), isOdbcClient),
                            type.esType.toUpperCase(Locale.ROOT),
                            type.displaySize,
                            // TODO: is the buffer_length correct?
                            type.size,
                            // no DECIMAL support
                            null,
                            odbcCompatible(DataTypes.metaSqlRadix(type), isOdbcClient),
                            // everything is nullable
                            odbcCompatible(DatabaseMetaData.columnNullable, isOdbcClient),
                            // no remarks
                            null,
                            // no column def
                            null,
                            // SQL_DATA_TYPE apparently needs to be same as DATA_TYPE except for datetime and interval data types
                            odbcCompatible(DataTypes.metaSqlDataType(type), isOdbcClient),
                            // SQL_DATETIME_SUB ?
                            odbcCompatible(DataTypes.metaSqlDateTimeSub(type), isOdbcClient),
                            // char octet length
                            type.isString() || type == DataType.BINARY ? type.size : null,
                            // position
                            pos,
                            "YES",
                            null,
                            null,
                            null,
                            null,
                            "NO",
                            "NO"
                            ));
                }
            }
            if (field.getProperties() != null) {
                fillInRows(clusterName, indexName, field.getProperties(), name, rows, columnMatcher, isOdbcClient);
            }
        }
    }
    
    private static Object odbcCompatible(Integer value, boolean isOdbcClient) {
        if (isOdbcClient && value != null) {
            return Short.valueOf(value.shortValue());
        }
        return value;
    }

    @Override
    public int hashCode() {
        return Objects.hash(catalog, index, pattern, columnPattern);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }

        SysColumns other = (SysColumns) obj;
        return Objects.equals(catalog, other.catalog)
                && Objects.equals(index, other.index)
                && Objects.equals(pattern, other.pattern)
                && Objects.equals(columnPattern, other.columnPattern);
    }
}