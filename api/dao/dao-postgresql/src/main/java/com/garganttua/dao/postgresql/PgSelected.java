package com.garganttua.dao.postgresql;

import com.garganttua.dao.postgresql.schema.PgColumn;
import com.garganttua.dao.postgresql.schema.PgColumnKind;
import com.garganttua.dao.postgresql.schema.PgNaming;

/**
 * A column as it appears in a select list, paired with its model so the cell can be decoded.
 *
 * <p>
 * The select expression is not always the bare column: a PostGIS geometry is selected as
 * {@code ST_AsGeoJSON(...)}, because its wire form is WKB, which no Java type here decodes, while
 * GeoJSON text is exactly what {@code PgJson.GEO} reads.
 * </p>
 *
 * @param column     the column model
 * @param expression the select-list expression, identifiers quoted
 */
record PgSelected(PgColumn column, String expression) {

    /**
     * The select expression of a column.
     *
     * @param qualifier the table alias to qualify with, or null for none
     * @param column    the column
     * @return the selected column
     */
    static PgSelected of(String qualifier, PgColumn column) {
        String quoted = PgNaming.quote(column.name());
        String ref = qualifier == null ? quoted : qualifier + "." + quoted;
        String expression = column.kind() == PgColumnKind.GEOMETRY
                ? "ST_AsGeoJSON(" + ref + ") AS " + quoted
                : ref;
        return new PgSelected(column, expression);
    }
}
