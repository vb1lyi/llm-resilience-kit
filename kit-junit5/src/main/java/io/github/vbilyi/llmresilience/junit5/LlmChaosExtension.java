package io.github.vbilyi.llmresilience.junit5;

import io.github.vbilyi.llmresilience.core.scenario.ChaosScenario;
import io.github.vbilyi.llmresilience.core.scenario.Layer;
import io.github.vbilyi.llmresilience.core.scenario.ScenarioCatalog;
import io.github.vbilyi.llmresilience.core.sut.ChaosEnvironment;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ExtensionContext.Store;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.TestTemplateInvocationContext;
import org.junit.jupiter.api.extension.TestTemplateInvocationContextProvider;
import org.junit.platform.commons.support.AnnotationSupport;
import org.junit.platform.commons.support.ReflectionSupport;
import org.mockserver.client.MockServerClient;

/**
 * The extension behind {@link LlmChaosSuite} and {@link LlmChaosTest}; it is registered by those annotations and
 * not meant to be used directly.
 *
 * <ul>
 *   <li>Starts the suite's {@link ChaosEnvironment} on first use and keeps it in JUnit's root store, so all test
 *       classes share it and JUnit closes it when the run ends.</li>
 *   <li>Turns each {@code @LlmChaosTest} method into one invocation per selected scenario
 *       (see {@code ScenarioInvocation} for what happens around each).</li>
 *   <li>Resolves the environment and its {@code MockServerClient} as parameters.</li>
 *   <li>Collects every outcome and hands them to the {@link ChaosRunListener}s at the end of the run.</li>
 * </ul>
 */
public final class LlmChaosExtension implements TestTemplateInvocationContextProvider, ParameterResolver {

    private static final Namespace NAMESPACE = Namespace.create(LlmChaosExtension.class);

    /** Created by JUnit. */
    public LlmChaosExtension() {}

    // ---- one invocation per scenario ----

    @Override
    public boolean supportsTestTemplate(ExtensionContext context) {
        return AnnotationSupport.isAnnotated(context.getTestMethod(), LlmChaosTest.class);
    }

    @Override
    public Stream<TestTemplateInvocationContext> provideTestTemplateInvocationContexts(ExtensionContext context) {
        LlmChaosSuite suite = suiteOf(context);
        LlmChaosTest test = AnnotationSupport.findAnnotation(context.getTestMethod(), LlmChaosTest.class).orElseThrow();
        ChaosEnvironment environment = environment(context, suite);
        ChaosRunRecorder recorder = recorder(context);

        return select(test, environment.scenarios()).stream()
                .map(scenario -> new ScenarioInvocation(scenario, environment, recorder, suite.recoveryProbe()));
    }

    /** Ids first, in the order written, then the scenarios of each layer; no scenario twice. */
    static List<ChaosScenario> select(LlmChaosTest test, ScenarioCatalog catalog) {
        if (test.all()) {
            if (test.scenarios().length > 0 || test.layers().length > 0) {
                throw new ExtensionConfigurationException(
                        "@LlmChaosTest: all = true already selects every scenario; remove scenarios/layers or all");
            }
            return catalog.all();
        }
        Set<ChaosScenario> selected = new LinkedHashSet<>();
        for (String id : test.scenarios()) {
            try {
                selected.add(catalog.get(id));
            } catch (NoSuchElementException e) {
                throw new ExtensionConfigurationException("@LlmChaosTest: " + e.getMessage(), e);
            }
        }
        for (Layer layer : test.layers()) {
            selected.addAll(catalog.byLayer(layer));
        }
        if (selected.isEmpty()) {
            throw new ExtensionConfigurationException(
                    "@LlmChaosTest selects no scenario: set scenarios = ..., layers = ... or all = true");
        }
        return List.copyOf(selected);
    }

    // ---- parameters available to every method of a suite class ----

    @Override
    public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
        Class<?> type = parameter.getParameter().getType();
        if (type == MockServerClient.class) {
            return true;
        }
        return ChaosEnvironment.class.isAssignableFrom(type) && type.isAssignableFrom(suiteOf(context).environment());
    }

    @Override
    public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
        ChaosEnvironment environment = environment(context, suiteOf(context));
        return parameter.getParameter().getType() == MockServerClient.class ? environment.mockServer() : environment;
    }

    // ---- shared state in the root store ----

    private static LlmChaosSuite suiteOf(ExtensionContext context) {
        return AnnotationSupport.findAnnotation(context.getRequiredTestClass(), LlmChaosSuite.class)
                .orElseThrow(() -> new ExtensionConfigurationException(context.getRequiredTestClass().getName()
                        + " must be annotated with @LlmChaosSuite(environment = ...)"));
    }

    private static ChaosEnvironment environment(ExtensionContext context, LlmChaosSuite suite) {
        Store store = context.getRoot().getStore(NAMESPACE);
        return store.computeIfAbsent(suite.environment(), StartedEnvironment::start, StartedEnvironment.class).get();
    }

    private static ChaosRunRecorder recorder(ExtensionContext context) {
        Store store = context.getRoot().getStore(NAMESPACE);
        return store.computeIfAbsent(ChaosRunRecorder.class, key -> new ChaosRunRecorder(), ChaosRunRecorder.class);
    }

    /**
     * The one environment of a run, or the reason it could not be started. A failed start is remembered and
     * rethrown to every later test, so a broken environment costs one startup timeout, not one per test class.
     */
    private record StartedEnvironment(ChaosEnvironment environment, RuntimeException failure) implements AutoCloseable {

        static StartedEnvironment start(Class<? extends ChaosEnvironment> type) {
            try {
                return new StartedEnvironment(ReflectionSupport.newInstance(type), null);
            } catch (Exception | LinkageError e) { // a constructor can throw anything; class loading can fail too
                return new StartedEnvironment(null, new ExtensionConfigurationException(
                        "Cannot start the environment " + type.getName() + ": " + e, e));
            }
        }

        ChaosEnvironment get() {
            if (failure != null) {
                throw failure;
            }
            return environment;
        }

        @Override
        public void close() {
            if (environment != null) {
                environment.close();
            }
        }
    }
}
