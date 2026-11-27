package com.garganttua.dao.sql;

import java.util.Map;

import com.garganttua.dao.sql.schema.SqlChildTable;
import com.garganttua.dao.sql.schema.SqlTable;

/**
 * A database's way of keeping collection digests honest — the optional capability that lets a write skip the
 * collections it would rewrite identically.
 *
 * <p>
 * The digest of the rows each collection would be written as is stored on the main row; a write whose collection
 * has the stored digest leaves its rows alone. That is only safe if anything else that touches the rows clears the
 * digest — which the database does (triggers), unless the DAO itself is the writer: the DAO raises a flag for its
 * transaction, the triggers honour it. A dialect without this capability has every write rewrite every collection —
 * correct, only slower.
 * </p>
 */
public interface ISqlListDigests {

    /** {@return the SQL expression that raises "the DAO is writing" for the current transaction} */
    String flagExpression();

    /** {@return the clause that locks the row its stored digests are read from, until the transaction ends} */
    String lockForUpdate();

    /** {@return the digest column of each collection of {@code table}, in collection order} */
    Map<SqlChildTable, String> columns(SqlTable table);
}
