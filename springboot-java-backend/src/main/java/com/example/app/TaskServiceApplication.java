package com.example.app;

import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.core.NestedExceptionUtils;

/**
 * Entry point. The sibling zero-dependency service wires everything by hand in a composition root;
 * here the container does it, and the explicit wiring that remains lives in
 * {@code com.example.app.config}.
 */
@SpringBootApplication
public class TaskServiceApplication {

    /** Exit code reserved for "the configuration is wrong", matching the sibling service. */
    private static final int CONFIG_INVALID = 2;

    public static void main(String[] args) {
        try {
            SpringApplication.run(TaskServiceApplication.class, args);
        } catch (Throwable failure) {
            // A binding failure kills the refresh, so an ExitCodeExceptionMapper bean — which needs
            // a context to be consulted — would never run. Hence the explicit catch.
            if (isConfigurationFailure(failure)) {
                System.err.println("config.invalid: "
                        + NestedExceptionUtils.getMostSpecificCause(failure).getMessage());
                System.exit(CONFIG_INVALID);
            }
            System.exit(1);
        }
    }

    private static boolean isConfigurationFailure(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof BindValidationException
                    || t instanceof ConfigurationPropertiesBindException
                    || t instanceof BindException) {
                return true;
            }
            if (t instanceof BeanCreationException e && e.getCause() == null) {
                return false;
            }
        }
        return false;
    }
}
