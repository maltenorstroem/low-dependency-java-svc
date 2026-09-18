package com.example.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point. The sibling zero-dependency service wires everything by hand in a composition
 * root; here the container does it, and the explicit wiring that remains lives in
 * {@code com.example.app.config}.
 */
@SpringBootApplication
public class TaskServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TaskServiceApplication.class, args);
    }
}
