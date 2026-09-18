package com.example.app.testing;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * A 100-line test runner, so the build has no test-framework dependency. Finds every class named
 * {@code *Test} under the given directory, runs each {@link Test} method on a fresh instance, and
 * exits non-zero on any failure. Swap for JUnit at any time: the tests are plain methods.
 */
public final class TestRunner {

    private TestRunner() {}

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : "target/test-classes");
        List<String> classNames;
        try (Stream<Path> files = Files.walk(root)) {
            classNames = files
                    .map(p -> root.relativize(p).toString())
                    .filter(p -> p.endsWith("Test.class"))
                    .map(p -> p.substring(0, p.length() - ".class".length()).replace('/', '.').replace('\\', '.'))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot scan " + root, e);
        }

        int passed = 0;
        int failed = 0;
        long start = System.nanoTime();
        for (String className : classNames) {
            Class<?> type = Class.forName(className);
            List<Method> tests = Arrays.stream(type.getDeclaredMethods())
                    .filter(m -> m.isAnnotationPresent(Test.class))
                    .sorted(Comparator.comparing(Method::getName))
                    .toList();
            for (Method test : tests) {
                String name = type.getSimpleName() + "." + test.getName();
                if (Modifier.isStatic(test.getModifiers()) || test.getParameterCount() != 0) {
                    System.out.println("FAIL " + name + ": @Test methods must be non-static with no parameters");
                    failed++;
                    continue;
                }
                try {
                    Object instance = type.getDeclaredConstructor().newInstance();
                    test.setAccessible(true);
                    try {
                        test.invoke(instance);
                    } finally {
                        if (instance instanceof AutoCloseable closeable) {
                            closeable.close();
                        }
                    }
                    passed++;
                    System.out.println("ok   " + name);
                } catch (InvocationTargetException e) {
                    failed++;
                    System.out.println("FAIL " + name + ": " + e.getCause());
                    e.getCause().printStackTrace(System.out);
                } catch (Exception e) {
                    failed++;
                    System.out.println("FAIL " + name + ": " + e);
                }
            }
        }
        System.out.printf("%n%d passed, %d failed in %d ms%n", passed, failed, (System.nanoTime() - start) / 1_000_000);
        System.exit(failed == 0 ? 0 : 1);
    }
}
