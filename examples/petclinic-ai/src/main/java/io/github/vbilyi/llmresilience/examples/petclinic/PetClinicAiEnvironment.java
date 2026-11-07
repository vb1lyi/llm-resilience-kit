package io.github.vbilyi.llmresilience.examples.petclinic;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import org.mockserver.client.MockServerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mockserver.MockServerContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The full test environment for Spring PetClinic AI: MockServer as the LLM, Postgres as the database,
 * and the SUT itself, all on one Docker network.
 *
 * <pre>
 *   tests ──HTTP──▶ sut:8080 ──/v1/chat/completions, /v1/embeddings──▶ llm-mock:1080 (MockServer)
 *     │                └──JDBC──▶ sut-db:5432 (Postgres)
 *     └──JDBC (host port)──▶ sut-db
 * </pre>
 *
 * <p><b>Lifecycle.</b> {@link #shared()} starts the environment once per test JVM and every test class re-uses it
 * (startup is ~15–30 s). Containers are stopped by a JVM shutdown hook, and by Testcontainers' Ryuk as a backstop.
 * Nothing is re-used <em>between</em> Gradle runs.
 *
 * <p><b>State between tests.</b> Call {@link #resetState()} before each test: it clears all MockServer expectations
 * and recorded requests and resets the database to its seed. The SUT's chat memory is global and cannot be reset
 * over HTTP (ADR-006) — mock expectations must therefore match on the last message only.
 *
 * <p><b>Configuration.</b> Images come from the system properties {@code sut.image}, {@code db.image} and
 * {@code mockserver.image}, which Gradle sets from {@code sut.properties} and the version catalog.
 * The SUT is configured only through environment variables (ADR-002) and keeps its shipped retry and timeout
 * defaults (ADR-005).
 */
public final class PetClinicAiEnvironment {

    /** Network alias of MockServer; the SUT's OpenAI base URL points here. */
    public static final String LLM_MOCK_ALIAS = "llm-mock";

    /** The SUT's OpenAI base URL inside the Docker network (no {@code /v1}: the SUT adds it). */
    public static final String LLM_BASE_URL = "http://" + LLM_MOCK_ALIAS + ":" + MockServerContainer.PORT;

    /** API key given to the SUT. Never a real key: the SUT only ever talks to MockServer. */
    public static final String DUMMY_API_KEY = "sk-dummy-not-a-real-key";

    private static final Logger log = LoggerFactory.getLogger(PetClinicAiEnvironment.class);

    private static final String DB_ALIAS = "sut-db";
    private static final String DB_NAME = "petclinic";
    private static final String DB_USER = "petclinic";
    private static final String DB_PASSWORD = "petclinic";
    private static final int SUT_PORT = 8080;

    /** MockServer must stay bounded: an unbounded tool loop got it OOM-killed in the spike (risk R11). */
    private static final String MOCKSERVER_MAX_LOG_ENTRIES = "2000";
    private static final long MOCKSERVER_MEMORY_BYTES = 768L * 1024 * 1024;

    private static PetClinicAiEnvironment shared;

    private final Network network;
    private final MockServerContainer mock;
    private final PostgreSQLContainer db;
    private final GenericContainer<?> sut;
    private final MockServerClient mockServerClient;
    private final PetClinicDatabase database;
    private final Duration startupTime;

    private PetClinicAiEnvironment(String sutImage, String dbImage, String mockServerImage) {
        requireDummyKey(DUMMY_API_KEY);
        network = Network.newNetwork();

        mock = new MockServerContainer(DockerImageName.parse(mockServerImage))
                .withNetwork(network)
                .withNetworkAliases(LLM_MOCK_ALIAS)
                .withEnv("MOCKSERVER_MAX_LOG_ENTRIES", MOCKSERVER_MAX_LOG_ENTRIES)
                .withEnv("JAVA_TOOL_OPTIONS", "-XX:MaxRAMPercentage=70")
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(MOCKSERVER_MEMORY_BYTES));

        db = new PostgreSQLContainer(DockerImageName.parse(dbImage))
                .withNetwork(network)
                .withNetworkAliases(DB_ALIAS)
                .withDatabaseName(DB_NAME)
                .withUsername(DB_USER)
                .withPassword(DB_PASSWORD);

        sut = new GenericContainer<>(DockerImageName.parse(sutImage))
                .withNetwork(network)
                .withExposedPorts(SUT_PORT)
                .withEnv(sutEnvironment())
                .dependsOn(mock, db)
                .waitingFor(Wait.forHttp("/actuator/health").forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(3)))
                .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("sut")).withSeparateOutputStreams());

        long t0 = System.nanoTime();
        Startables.deepStart(sut).join(); // starts mock + db in parallel, then the SUT
        startupTime = Duration.ofNanos(System.nanoTime() - t0);

        mockServerClient = new MockServerClient(mock.getHost(), mock.getServerPort());
        database = new PetClinicDatabase(db.getJdbcUrl(), DB_USER, DB_PASSWORD);
        log.info("PetClinic AI environment started in {} ms (sut={}, db={}, mock={}) - app at {}",
                startupTime.toMillis(), sutImage, dbImage, mockServerImage, baseUrl());
    }

    /**
     * Returns the environment for this JVM, starting it on first use.
     *
     * @return the shared, running environment
     */
    public static synchronized PetClinicAiEnvironment shared() {
        if (shared == null) {
            shared = new PetClinicAiEnvironment(
                    requiredProperty("sut.image"), requiredProperty("db.image"), requiredProperty("mockserver.image"));
            Runtime.getRuntime().addShutdownHook(new Thread(shared::stop, "petclinic-env-shutdown"));
        }
        return shared;
    }

    /**
     * Base URL of the running SUT, reachable from the test JVM.
     *
     * @return e.g. {@code http://localhost:49153}
     */
    public String baseUrl() {
        return "http://" + sut.getHost() + ":" + sut.getMappedPort(SUT_PORT);
    }

    /**
     * Client for arming and inspecting the LLM mock.
     *
     * @return the MockServer client
     */
    public MockServerClient mockServer() {
        return mockServerClient;
    }

    /**
     * JDBC access to the SUT's database.
     *
     * @return the database accessor
     */
    public PetClinicDatabase database() {
        return database;
    }

    /**
     * How long it took to bring the whole environment up.
     *
     * @return the measured startup time
     */
    public Duration startupTime() {
        return startupTime;
    }

    /** Clears MockServer (expectations and recorded requests) and resets the DB to its seed. See ADR-006. */
    public void resetState() {
        mockServerClient.reset();
        database.resetToSeed();
    }

    private void stop() {
        sut.stop();
        mock.stop();
        db.stop();
        network.close();
    }

    private static Map<String, String> sutEnvironment() {
        return Map.of(
                "SPRING_AI_OPENAI_BASE_URL", LLM_BASE_URL,
                "OPENAI_API_KEY", DUMMY_API_KEY,
                "SPRING_PROFILES_ACTIVE", "postgres",
                "POSTGRES_URL", "jdbc:postgresql://" + DB_ALIAS + ":5432/" + DB_NAME,
                "POSTGRES_USER", DB_USER,
                "POSTGRES_PASS", DB_PASSWORD);
    }

    /** Fails fast if anything but an obvious dummy key would ever be handed to the SUT. */
    static void requireDummyKey(String key) {
        if (!key.toLowerCase(Locale.ROOT).contains("dummy")) {
            throw new IllegalStateException("Refusing to start: the SUT API key does not look like a dummy key");
        }
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("System property '" + name
                    + "' is not set. Run the tests through Gradle (it sets it from sut.properties).");
        }
        return value;
    }
}
