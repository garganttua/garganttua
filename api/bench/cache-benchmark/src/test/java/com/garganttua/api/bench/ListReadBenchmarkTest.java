package com.garganttua.api.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.Serializable;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.garganttua.api.commons.context.IDomain;
import com.garganttua.api.commons.context.dsl.IApiBuilder;
import com.garganttua.api.commons.pageable.Pageable;
import com.garganttua.api.commons.repository.IRepository;
import com.garganttua.api.commons.service.IOperationResponse;
import com.garganttua.api.commons.service.Page;
import com.garganttua.api.core.caller.Caller;
import com.garganttua.core.reflection.IClass;

/**
 * The cost of a list read, per element: a 12-field domain, 30 000 rows on a real MongoDB, read whole through the
 * pipeline ({@code READ_ALL} → {@code getEntitiesPage}), as palliad's map read. Run with
 * {@code mvn -Pbench test -pl :garganttua-api-cache-benchmark -Dbench=true -Dtest=ListReadBenchmarkTest}; set
 * {@code JDK_JAVA_OPTIONS=-XX:StartFlightRecording=...} to profile it.
 */
class ListReadBenchmarkTest {

    private static final int ROWS = 30_000;
    private static final int WARMUP = 5;
    private static final int RUNS = 15;

    public static class Center implements Serializable {
        private static final long serialVersionUID = 1L;
        private String id;
        private String uuid;
        private String tenantId;
        private Boolean superTenant = false;
        private String name;
        private String kind;
        private String city;
        private String zip;
        private String phone;
        private Double latitude;
        private Double longitude;
        private Integer beds;
        public String getId() { return id; }
        public void setId(String v) { this.id = v; }
        public String getUuid() { return uuid; }
        public void setUuid(String v) { this.uuid = v; }
        public String getTenantId() { return tenantId; }
        public void setTenantId(String v) { this.tenantId = v; }
        public Boolean getSuperTenant() { return superTenant; }
        public void setSuperTenant(Boolean v) { this.superTenant = v; }
        public String getName() { return name; }
        public void setName(String v) { this.name = v; }
        public String getKind() { return kind; }
        public void setKind(String v) { this.kind = v; }
        public String getCity() { return city; }
        public void setCity(String v) { this.city = v; }
        public String getZip() { return zip; }
        public void setZip(String v) { this.zip = v; }
        public String getPhone() { return phone; }
        public void setPhone(String v) { this.phone = v; }
        public Double getLatitude() { return latitude; }
        public void setLatitude(Double v) { this.latitude = v; }
        public Double getLongitude() { return longitude; }
        public void setLongitude(Double v) { this.longitude = v; }
        public Integer getBeds() { return beds; }
        public void setBeds(Integer v) { this.beds = v; }
    }

    public static class CenterDto extends Center {
        private static final long serialVersionUID = 1L;
    }

    @Test
    void listRead() throws Exception {
        assumeTrue(Boolean.getBoolean("bench"), "-Dbench=true not set");
        try (BenchApi bench = new BenchApi()) {
            bench.reset();
            AtomicLong queries = new AtomicLong();
            IApiBuilder builder = BenchApi.newBuilder();
            builder.domain(IClass.getClass(Center.class))
                    .tenant(true).superTenant("superTenant")
                    .entity().id("id").uuid("uuid").tenantId("tenantId").up()
                    .dto(IClass.getClass(CenterDto.class)).id("id").uuid("uuid").tenantId("tenantId")
                    .db(bench.dao(BenchApi.Store.MONGODB, "centers", queries)).up()
                    .security().disable(true).up().up();
            IDomain<?> centers = BenchApi.start(builder).getDomain("centers").orElseThrow();
            seed(centers.getRepository());
            long[] millis = new long[RUNS];
            for (int i = 0; i < WARMUP + RUNS; i++) {
                long t = System.nanoTime();
                IOperationResponse response = centers.request().readAll().caller(Caller.createTenantCaller("T1"))
                        .page(new Pageable(0, ROWS)).execute();
                long elapsed = (System.nanoTime() - t) / 1_000_000;
                Object body = response.getResponse();
                List<?> rows = body instanceof Page page ? page.entities() : (List<?>) body;
                assertEquals(ROWS, rows.size());
                if (i >= WARMUP) {
                    millis[i - WARMUP] = elapsed;
                }
            }
            java.util.Arrays.sort(millis);
            System.out.printf(Locale.ROOT, "LIST READ %d rows x 12 fields: median %d ms, min %d ms, max %d ms "
                    + "(%.1f µs per row)%n", ROWS, millis[RUNS / 2], millis[0], millis[RUNS - 1],
                    millis[RUNS / 2] * 1000d / ROWS);
        }
    }

    private static void seed(IRepository repository) throws Exception {
        Random random = new Random(5);
        for (int i = 0; i < ROWS; i++) {
            Center center = new Center();
            center.setId("center-" + i);
            center.setUuid("uuid-center-" + i);
            center.setTenantId("T1");
            center.setName("Centre " + i);
            center.setKind(i % 3 == 0 ? "ehpad" : "usp");
            center.setCity("Ville " + (i % 500));
            center.setZip(String.format(Locale.ROOT, "%05d", i % 95_000));
            center.setPhone("01 23 45 " + (i % 100));
            center.setLatitude(42.5 + random.nextDouble() * 8.5);
            center.setLongitude(-4.5 + random.nextDouble() * 12.5);
            center.setBeds(random.nextInt(200));
            repository.save(center);
        }
    }
}
