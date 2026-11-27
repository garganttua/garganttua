package com.garganttua.dao.postgresql.parity;

import javax.sql.DataSource;

import com.garganttua.dao.postgresql.PgDao;
import com.garganttua.dao.postgresql.PgSchemaRegistry;
import com.garganttua.dao.postgresql.PgTestDatabase;
import com.garganttua.dao.postgresql.schema.SchemaMode;
import com.garganttua.dao.sql.AbstractSqlDao;
import com.garganttua.dao.sql.SqlSchemaRegistry;

/** The parity scenarios on the embedded PostgreSQL. */
public final class PgParityEngine implements ParityEngine {

    @Override
    public String name() {
        return "PostgreSQL";
    }

    @Override
    public DataSource freshDatabase() {
        return PgTestDatabase.freshDatabase();
    }

    @Override
    public AbstractSqlDao dao(DataSource database, String domainName, SqlSchemaRegistry registry) {
        PgSchemaRegistry pgRegistry = registry instanceof PgSchemaRegistry pg ? pg : new PgSchemaRegistry();
        return new PgDao(database, domainName, pgRegistry, SchemaMode.CREATE);
    }

    @Override
    public boolean storesNul() {
        return false;
    }

    @Override
    public boolean isPostgres() {
        return true;
    }
}
