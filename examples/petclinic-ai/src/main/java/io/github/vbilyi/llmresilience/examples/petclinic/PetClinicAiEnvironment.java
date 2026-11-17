package io.github.vbilyi.llmresilience.examples.petclinic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.vbilyi.llmresilience.core.scenario.ScenarioCatalog;
import io.github.vbilyi.llmresilience.core.sut.ChaosEnvironment;
import io.github.vbilyi.llmresilience.core.sut.ChatReply;
import io.github.vbilyi.llmresilience.core.sut.ChatTimeoutException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
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
 * <p><b>Lifecycle.</b> The constructor starts everything (~15–30 s) and returns when the SUT is healthy. Tests do
 * not call it: {@code @LlmChaosSuite(environment = PetClinicAiEnvironment.class)} makes the kit create one instance
 * per test JVM, share it between test classes and {@link #close()} it at the end (Testcontainers' Ryuk is the
 * backstop). Nothing is re-used <em>between</em> Gradle runs.
 *
 * <p><b>State between tests.</b> {@link #resetState()} runs before each scenario: it clears all MockServer expectations
 * and recorded requests and resets the database to its seed. The SUT's chat memory is global and cannot be reset
 * over HTTP (ADR-006), and the SUT may still be working for the previous test — so each test marks its user
 * message with a {@code RunMarker} and scenarios arm only that run's requests (ADR-007).
 *
 * <p><b>Configuration.</b> Images come from the system properties {@code sut.image}, {@code db.image} and
 * {@code mockserver.image}, which Gradle sets from {@code sut.properties} and the version catalog.
 * The SUT is configured only through environment variables (ADR-002) and keeps its shipped retry and timeout
 * defaults (ADR-005).
 */
public final class PetClinicAiEnvironment implements ChaosEnvironment {

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

    /** The real provider's host. Inside the SUT container it resolves to the container itself. */
    private static final String REAL_PROVIDER_HOST = "api.openai.com";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Network network;
    private final MockServerContainer mock;
    private final PostgreSQLContainer db;
    private final GenericContainer<?> sut;
    private final MockServerClient mockServerClient;
    private final PetClinicDatabase database;
    private final Duration startupTime;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /** Starts the environment with the images Gradle passes as system properties. */
    public PetClinicAiEnvironment() {
        String sutImage = requiredProperty("sut.image");
        String dbImage = requiredProperty("db.image");
        String mockServerImage = requiredProperty("mockserver.image");
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
                // Hard guarantee of "no calls to real LLM providers": even if the base-URL override were ever lost,
                // the SUT's default https://api.openai.com would go nowhere.
                .withExtraHost(REAL_PROVIDER_HOST, "127.0.0.1")
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
     * Base URL of the running SUT, reachable from the test JVM.
     *
     * @return e.g. {@code http://localhost:49153}
     */
    public String baseUrl() {
        return "http://" + sut.getHost() + ":" + sut.getMappedPort(SUT_PORT);
    }

    @Override
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

    /** The kit's built-in scenarios plus the ones that need PetClinic's tools. */
    @Override
    public ScenarioCatalog scenarios() {
        return PetClinicScenarios.catalog();
    }

    /** Clears MockServer (expectations and recorded requests) and resets the DB to its seed. See ADR-006. */
    @Override
    public void resetState() {
        mockServerClient.reset();
        database.resetToSeed();
    }

    /**
     * Sends the message exactly as the chat UI does: {@code POST /chat} with the text as a JSON string literal
     * (spike Q4). The UI shows the response body whatever the status, so that is what the user sees.
     */
    @Override
    public ChatReply chat(String userMessage, Duration timeout) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/chat"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(userMessage)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return new ChatReply(response.statusCode(), response.body());
        } catch (HttpTimeoutException e) {
            throw new ChatTimeoutException(timeout, e);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot encode the chat message", e);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot reach the SUT at " + baseUrl(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the SUT", e);
        }
    }

    @Override
    public void close() {
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
