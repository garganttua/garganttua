package com.garganttua.api.core.integ.writescan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.ApiException;
import com.garganttua.api.commons.caller.ICaller;
import com.garganttua.api.commons.context.IApi;
import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.context.dsl.IApiBuilder;
import com.garganttua.api.commons.dto.annotations.Dto;
import com.garganttua.api.commons.dto.annotations.DtoId;
import com.garganttua.api.commons.dto.annotations.DtoTenantId;
import com.garganttua.api.commons.dto.annotations.DtoUuid;
import com.garganttua.api.commons.entity.EntityUpdateRule;
import com.garganttua.api.commons.entity.annotations.AuthorizeCreate;
import com.garganttua.api.commons.entity.annotations.AuthorizeUpdate;
import com.garganttua.api.commons.entity.annotations.Entity;
import com.garganttua.api.commons.entity.annotations.EntityId;
import com.garganttua.api.commons.entity.annotations.EntitySuperTenant;
import com.garganttua.api.commons.entity.annotations.EntityTenant;
import com.garganttua.api.commons.entity.annotations.EntityTenantId;
import com.garganttua.api.commons.entity.annotations.EntityUuid;
import com.garganttua.api.commons.service.IOperationResponse;
import com.garganttua.api.commons.service.OperationResponseCode;
import com.garganttua.api.core.api.Api;
import com.garganttua.api.core.api.ApiBuilder;
import com.garganttua.api.core.caller.Caller;
import com.garganttua.api.core.integ.crud.AbstractCrudIntegrationTest;
import com.garganttua.core.dsl.IAutomaticBuilder;
import com.garganttua.core.reflection.IClass;

/**
 * {@code @AuthorizeCreate} / {@code @AuthorizeUpdate} auto-detection — the annotation counterpart of
 * {@code entity().create(...)} / {@code entity().update(...)}. The scanner must carry the authority
 * AND the {@code ignoreNull} policy onto the entity definition.
 */
@DisplayName("@AuthorizeCreate / @AuthorizeUpdate auto-detection wires the write whitelists")
class AuthorizeWriteAnnotationScanTest extends AbstractCrudIntegrationTest {

	@Entity
	@EntityTenant
	public static class Article {
		@EntityId private String id;
		@EntityUuid private String uuid;
		@EntityTenantId private String tenantId;
		@EntitySuperTenant private Boolean superTenant = false;

		@AuthorizeCreate
		@AuthorizeUpdate
		private String title;

		/** Opts into PATCH semantics: a null body value leaves the stored summary alone. */
		@AuthorizeCreate
		@AuthorizeUpdate(ignoreNull = true)
		private String summary;

		@AuthorizeCreate(authority = "article-publish")
		@AuthorizeUpdate(authority = "article-publish")
		private Boolean published;

		/** Declared on neither whitelist — never writable by a client. */
		private String internalNote;

		public String getId() { return id; }
		public void setId(String id) { this.id = id; }
		public String getUuid() { return uuid; }
		public void setUuid(String uuid) { this.uuid = uuid; }
		public String getTenantId() { return tenantId; }
		public void setTenantId(String tenantId) { this.tenantId = tenantId; }
		public String getTitle() { return title; }
		public void setTitle(String title) { this.title = title; }
		public String getSummary() { return summary; }
		public void setSummary(String summary) { this.summary = summary; }
		public Boolean getPublished() { return published; }
		public void setPublished(Boolean published) { this.published = published; }
		public String getInternalNote() { return internalNote; }
		public void setInternalNote(String internalNote) { this.internalNote = internalNote; }
		public Boolean getSuperTenant() { return superTenant; }
		public void setSuperTenant(Boolean superTenant) { this.superTenant = superTenant; }
	}

	@Dto(entityClass = Article.class)
	public static class ArticleDto {
		@DtoId private String id;
		@DtoUuid private String uuid;
		@DtoTenantId private String tenantId;
		private String title;
		private String summary;
		private Boolean published;
		private String internalNote;
		private Boolean superTenant = false;

		public String getId() { return id; }
		public void setId(String id) { this.id = id; }
		public String getUuid() { return uuid; }
		public void setUuid(String uuid) { this.uuid = uuid; }
		public String getTenantId() { return tenantId; }
		public void setTenantId(String tenantId) { this.tenantId = tenantId; }
		public String getTitle() { return title; }
		public void setTitle(String title) { this.title = title; }
		public String getSummary() { return summary; }
		public void setSummary(String summary) { this.summary = summary; }
		public Boolean getPublished() { return published; }
		public void setPublished(Boolean published) { this.published = published; }
		public String getInternalNote() { return internalNote; }
		public void setInternalNote(String internalNote) { this.internalNote = internalNote; }
		public Boolean getSuperTenant() { return superTenant; }
		public void setSuperTenant(Boolean superTenant) { this.superTenant = superTenant; }
	}

	private final ICaller caller = Caller.createSuperCaller("superTenant");
	private IDomain<?> articles;

	@BeforeEach
	void setUp() throws ApiException {
		IApiBuilder builder = newBuilder();
		((ApiBuilder) builder).withPackage("com.garganttua.api.core.integ.writescan");
		((IAutomaticBuilder<?, ?>) builder).autoDetect(true);

		// The scanner builds the domain shape but does not wire a DAO.
		builder.domain(IClass.getClass(Article.class))
				.dto(IClass.getClass(ArticleDto.class))
					.db(new StubDao())
				.up()
				.security().disable(true).up()
			.up();

		IApi api = buildAndStart(builder);
		Map<String, IDomain<?>> domains = ((Api) api).getDomains();
		articles = domains.values().stream()
				.filter(d -> d.getEntityClass().represents(Article.class))
				.findFirst().orElse(null);
		assertNotNull(articles, "the @Entity-annotated Article should be registered as a domain");
	}

	private static EntityUpdateRule ruleFor(List<EntityUpdateRule> rules, String field) {
		return rules.stream()
				.filter(r -> field.equals(r.field().toString()))
				.findFirst().orElse(null);
	}

	@Test
	@DisplayName("@AuthorizeUpdate carries the field, the authority and the ignoreNull policy")
	void updateWhitelistWired() {
		List<EntityUpdateRule> rules = articles.getEntityDefinition().updates();

		assertEquals(3, rules.size(), "exactly the three @AuthorizeUpdate fields; got=" + rules);

		EntityUpdateRule title = ruleFor(rules, "title");
		assertNotNull(title, "title must be updatable");
		assertNull(title.authority(), "no authority() declared — the DSL's 'no gate' null");
		assertFalse(title.ignoreNull(), "ignoreNull defaults to false — a null erases");

		EntityUpdateRule summary = ruleFor(rules, "summary");
		assertNotNull(summary, "summary must be updatable");
		assertTrue(summary.ignoreNull(), "@AuthorizeUpdate(ignoreNull = true) must reach the definition");

		EntityUpdateRule published = ruleFor(rules, "published");
		assertNotNull(published, "published must be updatable");
		assertEquals("article-publish", published.authority(), "the declared authority must reach the definition");

		assertNull(ruleFor(rules, "internalNote"), "an unannotated field is not updatable");
	}

	@Test
	@DisplayName("@AuthorizeCreate wires the creation whitelist the same way")
	void createWhitelistWired() {
		var creates = articles.getEntityDefinition().creates();

		assertEquals(3, creates.size(), "exactly the three @AuthorizeCreate fields; got=" + creates);
		assertTrue(creates.stream().anyMatch(p -> "title".equals(p.getValue0().toString()) && p.getValue1() == null));
		assertTrue(creates.stream().anyMatch(p -> "summary".equals(p.getValue0().toString()) && p.getValue1() == null));
		assertTrue(creates.stream().anyMatch(
				p -> "published".equals(p.getValue0().toString()) && "article-publish".equals(p.getValue1())));
	}

	@Test
	@DisplayName("end to end: the annotated ignoreNull field survives a body that omits it, the others do not")
	void annotatedPolicyAppliesThroughTheUpdatePath() throws ApiException {
		Article seed = new Article();
		seed.setUuid("uuid-article");
		seed.setTenantId("superTenant");
		seed.setTitle("First draft");
		seed.setSummary("A summary worth keeping");
		articles.createOne(seed, caller);

		Article body = new Article();
		body.setTitle("Second draft");   // summary + published left null

		IOperationResponse response = articles.updateOne("uuid-article", body, caller);
		assertEquals(OperationResponseCode.UPDATED, response.getResponseCode(),
				"update should succeed. got=" + response.getResponseCode() + " / " + response.getResponse());

		Article result = (Article) response.getResponse();
		assertEquals("Second draft", result.getTitle());
		assertEquals("A summary worth keeping", result.getSummary(),
				"@AuthorizeUpdate(ignoreNull = true) — the omitted summary must survive");
		assertNull(result.getPublished(), "default policy — the omitted published is erased");
	}
}
