package com.garganttua.dao.sql;

import com.garganttua.dao.sql.schema.SqlColumn;
import com.garganttua.dao.sql.schema.SqlColumnKind;
import com.garganttua.dao.sql.schema.SqlNaming;

/**
 * A column as it appears in a select list, paired with its model so the cell can be decoded.
 *
 * <p>
 * The select expression is not always the bare column: a PostGIS geometry is selected as
 * {@code ST_AsGeoJSON(...)}, because its wire form is WKB, which no Java type here decodes, while
 * GeoJSON text is exactly what {@code GeoJsonCodec} reads.
 * </p>
 *
 * @param column     the column model
 * @param expression the select-list expression, identifiers quoted
 */
public record SqlSelected(SqlColumn column, String expression) {

    /**
     * The select expression of a column.
     *
     * @param qualifier the table alias to qualify with, or null for none
     * @param column    the column
     * @return the selected column
     */
    public static SqlSelected of(String qualifier, SqlColumn column) {
        String quoted = SqlNaming.quote(column.name());
        String ref = qualifier == null ? quoted : qualifier + "." + quoted;
        String expression = column.kind() == SqlColumnKind.GEOMETRY
                ? "ST_AsGeoJSON(" + ref + ") AS " + quoted
                : ref;
        return new SqlSelected(column, expression);
    }
}
