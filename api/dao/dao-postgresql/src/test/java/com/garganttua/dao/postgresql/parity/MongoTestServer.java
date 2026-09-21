package com.garganttua.dao.postgresql.parity;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Assumptions;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;

/**
 * A REAL MongoDB for the parity tests — one {@code mongod} per JVM, one fresh database per caller.
 *
 * <p>
 * The PostgreSQL DAO must behave like the MongoDB one. That is a claim about two engines, and it can
 * only be checked by running the same operations against both: this is the MongoDB half. The binary
 * is the official build, downloaded once into {@code ~/.cache/garganttua-test} and reused.
 * </p>
 *
 * <p>
 * When it cannot be obtained — an offline build with an empty cache, a platform with no matching
 * build — the parity tests are SKIPPED with a message saying so. They are never faked: a parity
 * test that ran against anything but MongoDB would prove nothing about MongoDB.
 * </p>
 */
public final class MongoTestServer {

    static final String VERSION = "7.0.14";
    private static final String URL = "https://fastdl.mongodb.org/linux/mongodb-linux-x86_64-ubuntu2204-"
            + VERSION + ".tgz";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static MongoClient client;
    private static String unavailable;

    private MongoTestServer() {
        // Static holder
    }

    /**
     * A brand-new, empty database on the shared server, or a skipped test when there is no server.
     *
     * @return the database
     */
    public static synchronized MongoDatabase freshDatabase() {
        if (client == null && unavailable == null) {
            start();
        }
        Assumptions.assumeTrue(unavailable == null, () -> "MongoDB parity tests skipped: " + unavailable);
        return client.getDatabase("t" + ProcessHandle.current().pid() + "_" + SEQUENCE.incrementAndGet());
    }

    private static void start() {
        try {
            String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
            String arch = System.getProperty("os.arch");
            if (!os.contains("linux") || !(arch.equals("amd64") || arch.equals("x86_64"))) {
                unavailable = "no MongoDB build wired for " + os + "/" + arch + " (Linux x86_64 only)";
                return;
            }
            Path mongod = binary();
            // On disk, not in a RAM-backed /tmp; and removed on shutdown — a suite that leaves its data
            // behind fills the host, as an earlier run of this very suite did.
            Path data = Files.createTempDirectory(Files.createDirectories(cache().resolve("data")), "mongod-");
            int port = freePort();
            Process process = new ProcessBuilder(mongod.toString(), "--dbpath", data.toString(),
                    "--port", String.valueOf(port), "--bind_ip", "127.0.0.1", "--quiet")
                    .redirectErrorStream(true)
                    .redirectOutput(data.resolve("mongod.log").toFile())
                    .start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> stop(process, data), "parity-mongod-shutdown"));
            awaitPort(port, process);
            client = MongoClients.create("mongodb://127.0.0.1:" + port);
        } catch (IOException | RuntimeException e) {
            unavailable = e.getClass().getSimpleName() + ": " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            unavailable = "interrupted while starting mongod";
        }
    }

    private static Path cache() {
        return Path.of(System.getProperty("user.home"), ".cache", "garganttua-test");
    }

    /** Stops mongod, waits for it, and deletes its data directory. */
    private static void stop(Process process, Path data) {
        process.destroy();
        try {
            process.waitFor(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try (java.util.stream.Stream<Path> files = Files.walk(data)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
        } catch (IOException ignored) {
            // Best effort at shutdown.
        }
    }

    /** The mongod binary, downloaded and extracted on first use. */
    private static Path binary() throws IOException, InterruptedException {
        Path home = cache().resolve("mongodb-" + VERSION);
        Path mongod = home.resolve("mongodb-linux-x86_64-ubuntu2204-" + VERSION).resolve("bin").resolve("mongod");
        if (Files.isExecutable(mongod)) {
            return mongod;
        }
        Files.createDirectories(home);
        Path archive = home.resolve("mongodb.tgz");
        HttpResponse<Path> response = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build()
                .send(HttpRequest.newBuilder(URI.create(URL)).timeout(Duration.ofMinutes(5)).build(),
                        HttpResponse.BodyHandlers.ofFile(archive));
        if (response.statusCode() != 200) {
            throw new IOException("download of " + URL + " answered " + response.statusCode());
        }
        Process tar = new ProcessBuilder("tar", "-xzf", archive.toString(), "-C", home.toString())
                .redirectErrorStream(true).start();
        if (!tar.waitFor(5, TimeUnit.MINUTES) || tar.exitValue() != 0) {
            throw new IOException("could not extract " + archive);
        }
        Files.deleteIfExists(archive);
        return mongod;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void awaitPort(int port, Process process) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            if (!process.isAlive()) {
                throw new IOException("mongod exited with " + process.exitValue() + " before accepting connections");
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
                return;
            } catch (IOException notYet) {
                TimeUnit.MILLISECONDS.sleep(200);
            }
        }
        throw new IOException("mongod did not accept connections within 60 s");
    }
}
