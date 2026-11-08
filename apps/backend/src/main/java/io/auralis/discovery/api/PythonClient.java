package io.auralis.discovery.api;

/** Validated internal wire messages. Calls must run outside database transactions. */
public interface PythonClient {
    String runtime();

    String start(String execution, String payload);

    String admission(String execution, String pythonBoot, String digest, String deadline);

    String cancel(String execution, String coordinatorBoot, String pythonBoot, String reason);

    final class Unavailable extends RuntimeException {
        public Unavailable() {
            super("PYTHON_CONTROL_UNAVAILABLE");
        }
    }
}
