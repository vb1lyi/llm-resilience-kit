package io.github.vbilyi.llmresilience.junit5;

import io.github.vbilyi.llmresilience.core.sut.ChaosEnvironment;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * Marks a test class that runs against a {@link ChaosEnvironment}.
 *
 * <p>The environment is created on first use, shared by every test class of the run and closed when the run ends.
 * Any test or lifecycle method of the class can take it as a parameter (as {@code ChaosEnvironment} or as its own
 * type), as well as its {@code MockServerClient}.
 *
 * <p>All suite classes share one SUT, one mock and one database, so their tests must never overlap. The annotation
 * carries a {@link ResourceLock}: even with JUnit's parallel execution switched on, suite classes run one at a time
 * and their tests one after another.
 *
 * <pre>{@code
 * @LlmChaosSuite(environment = PetClinicAiEnvironment.class)
 * class ChatResilienceChaosIT {
 *
 *     @LlmChaosTest(layers = Layer.TRANSPORT)
 *     void survives(ChaosScenario scenario, ChatDriver chat) { ... }
 * }
 * }</pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ExtendWith(LlmChaosExtension.class)
@ResourceLock(LlmChaosSuite.ENVIRONMENT_LOCK)
public @interface LlmChaosSuite {

    /** The JUnit resource every suite class locks: the shared environment. */
    String ENVIRONMENT_LOCK = "io.github.vbilyi.llmresilience.environment";

    /**
     * The environment to run against.
     *
     * @return a class with a public no-argument constructor
     */
    Class<? extends ChaosEnvironment> environment();

    /**
     * Whether to check, after every non-control scenario, that the SUT still answers a normal message
     * ({@code HAPPY_PATH}). A SUT that does not recover fails that scenario's invocation.
     *
     * @return {@code true} (the default) to probe
     */
    boolean recoveryProbe() default true;
}
