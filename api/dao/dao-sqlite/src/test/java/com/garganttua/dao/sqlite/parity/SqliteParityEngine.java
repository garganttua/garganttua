package com.garganttua.dao.sqlite.parity;

import javax.sql.DataSource;

import com.garganttua.dao.postgresql.parity.ParityEngine;
import com.garganttua.dao.sql.AbstractSqlDao;
import com.garganttua.dao.sql.SqlSchemaRegistry;
import com.garganttua.dao.sqlite.SqliteDao;
import com.garganttua.dao.sqlite.SqliteTestDatabase;
import com.garganttua.dao.sqlite.schema.SqliteSchemaMode;

/** The parity scenarios of the shared SQL core, run on SQLite against MongoDB. */
public final class SqliteParityEngine implements ParityEngine {

    @Override
    public String name() {
        return "SQLite";
    }

    @Override
    public DataSource freshDatabase() {
        return SqliteTestDatabase.freshDatabase();
    }

    @Override
    public AbstractSqlDao dao(DataSource database, String domainName, SqlSchemaRegistry registry) {
        return new SqliteDao(database, domainName, registry, SqliteSchemaMode.CREATE);
    }

    @Override
    public boolean storesNul() {
        return true;
    }
}
