package com.garganttua.api.starter.mongodb;

import java.util.Locale;
import java.util.Map;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.starter.AutoConfigurationContext;
import com.garganttua.api.commons.starter.IApiAutoConfiguration;
import com.garganttua.api.commons.starter.IConfig;
import com.garganttua.dao.mongodb.MongoDao;
import com.garganttua.dao.mongodb.MongoIndexMode;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;

/**
 * Auto-configures MongoDB persistence: reads {@code mongodb.uri} and
 * {@code mongodb.database} from the application config, opens a single
 * {@link MongoClient}, and registers a default DAO factory that yields a
 * {@link MongoDao} per domain (collection = domain name). Any domain whose dto
 * sets an explicit {@code .db(...)} keeps it — the factory is only consulted as
 * a fallback.
 *
 * <table>
 * <caption>Configuration</caption>
 * <tr><th>Key</th><th>Required</th><th>Meaning</th></tr>
 * <tr><td>{@code mongodb.uri}</td><td>yes</td><td>connection string,
 * {@code mongodb://host:27017}</td></tr>
 * <tr><td>{@code mongodb.database}</td><td>yes</td><td>database name</td></tr>
 * <tr><td>{@code mongodb.index.auto}</td><td>no</td><td>{@code create} (default): create the
 * declared indexes that are missing, warn about anything that cannot be created and start anyway;
 * {@code none}: touch no index at all; {@code strict}: as {@code create}, but refuse to start when
 * a declared index cannot be created</td></tr>
 * </table>
 *
 * <p>Runs at {@code order() = 0} (persistence before transport). Discovered via
 * {@link java.util.ServiceLoader}.
 */
public final class MongoAutoConfiguration implements IApiAutoConfiguration {

	/** The setting naming who creates the declared indexes — the MongoDB {@code postgresql.schema.auto}. */
	static final String INDEX_AUTO = "mongodb.index.auto";

	/** The boolean spellings the PostgreSQL twin uses, mapped onto the modes they mean here. */
	private static final Map<String, MongoIndexMode> BOOLEAN_ALIASES = Map.of(
			"true", MongoIndexMode.CREATE,
			"false", MongoIndexMode.NONE);

	@Override
	public int order() {
		return 0;
	}

	// CloseResource: the MongoClient is intentionally long-lived and handed to the
	// framework via context.registerResource(...), which owns and closes it.
	@SuppressWarnings("PMD.CloseResource")
	@Override
	public void apply(AutoConfigurationContext context) throws ApiException {
		String uri = context.config().getString("mongodb.uri")
				.orElseThrow(() -> new ApiException(
						"mongodb.uri is required by the MongoDB starter. Set it in application.yaml "
								+ "(mongodb.uri: mongodb://host:27017) or via GARGANTTUA_MONGODB_URI."));
		String database = context.config().getString("mongodb.database")
				.orElseThrow(() -> new ApiException(
						"mongodb.database is required by the MongoDB starter. Set it in application.yaml "
								+ "(mongodb.database: myapp) or via GARGANTTUA_MONGODB_DATABASE."));

		MongoIndexMode indexMode = indexMode(context.config());

		MongoClient client = MongoClients.create(uri);
		MongoDatabase mongoDatabase = client.getDatabase(database);
		context.registerResource(client);

		// One MongoDao per domain; the collection is the plural domain name.
		context.registerDefaultDao((domainName, dtoClass) -> new MongoDao(mongoDatabase, domainName, indexMode));
	}

	/**
	 * Reads {@code mongodb.index.auto}. Unset means {@link MongoIndexMode#CREATE}, so an application
	 * that never heard of the setting behaves as it always did — it declares no index, and no index
	 * is created.
	 *
	 * <p>
	 * A value that names no mode is an error rather than a silent fallback: a misspelt {@code strict}
	 * quietly downgraded to {@code create} would be a database not holding the constraint someone
	 * deliberately demanded, which is the exact failure this setting exists to prevent.
	 * </p>
	 *
	 * @param config the application configuration
	 * @return the mode to build every DAO with
	 * @throws ApiException when the configured value names no mode
	 */
	static MongoIndexMode indexMode(IConfig config) throws ApiException {
		String configured = config.getString(INDEX_AUTO).orElse("");
		if (configured.isBlank()) {
			return MongoIndexMode.CREATE;
		}
		// true/false are accepted too: the PostgreSQL twin, postgresql.schema.auto, is a boolean.
		MongoIndexMode fromBoolean = BOOLEAN_ALIASES.get(configured.trim().toLowerCase(Locale.ROOT));
		if (fromBoolean != null) {
			return fromBoolean;
		}
		return MongoIndexMode.parse(configured)
				.orElseThrow(() -> new ApiException(INDEX_AUTO + " is '" + configured
						+ "', which names no mode. Use 'create' (the default: create the declared indexes "
						+ "that are missing), 'none' (touch no index), or 'strict' (create, and refuse to "
						+ "start when one cannot be created)."));
	}
}
