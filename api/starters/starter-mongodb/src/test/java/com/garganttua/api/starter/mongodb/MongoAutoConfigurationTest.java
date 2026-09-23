package com.garganttua.api.starter.mongodb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.context.dsl.IApiBuilder;
import com.garganttua.api.commons.dao.IDao;
import com.garganttua.api.commons.dao.IDaoFactory;
import com.garganttua.api.commons.starter.AutoConfigurationContext;
import com.garganttua.api.commons.starter.IConfig;
import com.garganttua.dao.mongodb.MongoDao;
import com.garganttua.dao.mongodb.MongoIndexMode;

@DisplayName("MongoAutoConfiguration")
@ExtendWith(MockitoExtension.class)
class MongoAutoConfigurationTest {

	@Mock
	private IApiBuilder apiBuilder;

	@Mock
	private IConfig config;

	private final MongoAutoConfiguration autoConfig = new MongoAutoConfiguration();

	@Test
	@DisplayName("runs before transport (order 0)")
	void orderIsPersistence() {
		assertEquals(0, autoConfig.order());
	}

	@Nested
	@DisplayName("when mongodb.uri and mongodb.database are present")
	class Configured {

		@Test
		@DisplayName("registers a default DAO factory that yields a MongoDao whose collection is the domain name")
		void registersMongoDaoPerDomain() throws Exception {
			when(config.getString("mongodb.uri")).thenReturn(Optional.of("mongodb://localhost:27017"));
			when(config.getString("mongodb.database")).thenReturn(Optional.of("myapp"));

			autoConfig.apply(new AutoConfigurationContext(apiBuilder, config));

			ArgumentCaptor<IDaoFactory> captor = ArgumentCaptor.forClass(IDaoFactory.class);
			verify(apiBuilder).defaultDao(captor.capture());

			// The factory keys only on the domain name; the dto class is unused here.
			IDao dao = captor.getValue().create("users", null);
			MongoDao mongoDao = assertInstanceOf(MongoDao.class, dao);
			assertEquals("users", collectionNameOf(mongoDao),
					"the MongoDao collection must be the plural domain name");
		}

		private static String collectionNameOf(MongoDao dao) throws Exception {
			Field field = MongoDao.class.getDeclaredField("collectionName");
			field.setAccessible(true);
			return (String) field.get(dao);
		}
	}

	@Nested
	@DisplayName("mongodb.index.auto — who creates the declared indexes")
	class IndexMode {

		@Test
		@DisplayName("unset means create: an application that never heard of the setting behaves as before")
		void unsetIsCreate() throws Exception {
			when(config.getString(MongoAutoConfiguration.INDEX_AUTO)).thenReturn(Optional.empty());

			assertEquals(MongoIndexMode.CREATE, MongoAutoConfiguration.indexMode(config));
		}

		@Test
		@DisplayName("each mode is read by name, whatever the case")
		void namedModes() throws Exception {
			when(config.getString(MongoAutoConfiguration.INDEX_AUTO))
					.thenReturn(Optional.of("create"), Optional.of("NONE"), Optional.of(" Strict "));

			assertEquals(MongoIndexMode.CREATE, MongoAutoConfiguration.indexMode(config));
			assertEquals(MongoIndexMode.NONE, MongoAutoConfiguration.indexMode(config));
			assertEquals(MongoIndexMode.STRICT, MongoAutoConfiguration.indexMode(config));
		}

		@Test
		@DisplayName("true/false are accepted too, since the PostgreSQL twin is a boolean")
		void booleansAreAccepted() throws Exception {
			when(config.getString(MongoAutoConfiguration.INDEX_AUTO))
					.thenReturn(Optional.of("true"), Optional.of("false"));

			assertEquals(MongoIndexMode.CREATE, MongoAutoConfiguration.indexMode(config));
			assertEquals(MongoIndexMode.NONE, MongoAutoConfiguration.indexMode(config));
		}

		@Test
		@DisplayName("a value naming no mode is refused rather than silently downgraded — a misspelt "
				+ "'strict' must not become 'create'")
		void unknownValueIsRefused() {
			when(config.getString(MongoAutoConfiguration.INDEX_AUTO)).thenReturn(Optional.of("stric"));

			ApiException ex = assertThrows(ApiException.class,
					() -> MongoAutoConfiguration.indexMode(config));
			assertTrue(ex.getMessage().contains("stric"), ex.getMessage());
			assertTrue(ex.getMessage().contains("'strict'"), ex.getMessage());
		}

		@Test
		@DisplayName("the mode reaches every DAO the factory builds")
		void modeIsWiredIntoTheDao() throws Exception {
			when(config.getString("mongodb.uri")).thenReturn(Optional.of("mongodb://localhost:27017"));
			when(config.getString("mongodb.database")).thenReturn(Optional.of("myapp"));
			when(config.getString(MongoAutoConfiguration.INDEX_AUTO)).thenReturn(Optional.of("strict"));

			autoConfig.apply(new AutoConfigurationContext(apiBuilder, config));

			ArgumentCaptor<IDaoFactory> captor = ArgumentCaptor.forClass(IDaoFactory.class);
			verify(apiBuilder).defaultDao(captor.capture());

			assertEquals(MongoIndexMode.STRICT, indexModeOf((MongoDao) captor.getValue().create("users", null)));
		}

		private static MongoIndexMode indexModeOf(MongoDao dao) throws Exception {
			Field indexes = MongoDao.class.getDeclaredField("indexes");
			indexes.setAccessible(true);
			Object manager = indexes.get(dao);
			Field mode = manager.getClass().getDeclaredField("mode");
			mode.setAccessible(true);
			return (MongoIndexMode) mode.get(manager);
		}
	}

	@Nested
	@DisplayName("when required keys are missing")
	class Missing {

		@Test
		@DisplayName("a missing mongodb.uri fails with a pointed message")
		void missingUri() {
			when(config.getString("mongodb.uri")).thenReturn(Optional.empty());

			ApiException ex = assertThrows(ApiException.class,
					() -> autoConfig.apply(new AutoConfigurationContext(apiBuilder, config)));
			assertTrue(ex.getMessage().contains("mongodb.uri is required"), ex.getMessage());
		}

		@Test
		@DisplayName("a missing mongodb.database fails with a pointed message")
		void missingDatabase() {
			when(config.getString("mongodb.uri")).thenReturn(Optional.of("mongodb://localhost:27017"));
			when(config.getString("mongodb.database")).thenReturn(Optional.empty());

			ApiException ex = assertThrows(ApiException.class,
					() -> autoConfig.apply(new AutoConfigurationContext(apiBuilder, config)));
			assertTrue(ex.getMessage().contains("mongodb.database is required"), ex.getMessage());
		}
	}
}
