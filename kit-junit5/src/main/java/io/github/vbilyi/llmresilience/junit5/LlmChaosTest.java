package io.github.vbilyi.llmresilience.junit5;

import io.github.vbilyi.llmresilience.core.scenario.Layer;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Runs the test method once per selected scenario, in a class annotated with {@link LlmChaosSuite}.
 *
 * <p>Before each invocation the environment is reset and the scenario is armed. The method can take the
 * {@code ChaosScenario}, a {@code ChatDriver} bound to it, and the {@code RunMarker} as parameters. The invocation's
 * display name is the scenario id.
 *
 * <p>Scenarios are selected from the environment's catalog by {@link #scenarios() id}, by {@link #layers() layer},
 * or {@link #all() all of them}; ids and layers add up.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@TestTemplate
@ExtendWith(LlmChaosExtension.class)
public @interface LlmChaosTest {

    /**
     * Scenario ids, e.g. {@code RateLimit429.ID} or {@code "TOOL_CALL_LOOP"}. An unknown id fails the test.
     *
     * @return the ids to run, in this order
     */
    String[] scenarios() default {};

    /**
     * Layers whose scenarios are all run.
     *
     * @return the layers
     */
    Layer[] layers() default {};

    /**
     * Runs the whole catalog, control scenarios included.
     *
     * @return {@code true} to run every scenario
     */
    boolean all() default false;
}
