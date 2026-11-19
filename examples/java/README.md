# Java examples

Install the package into your local Maven repository, point it at a running Datadog Agent and run an
example:

```bash
make -C ../../java pack            # builds and installs codes.grow.packages:datadog-apm
export DD_AGENT_HOST=127.0.0.1      # Datadog Agent with the OTLP receiver on 4317/4318
export DD_SERVICE=checkout DD_ENV=dev
export DD_API_KEY=... DD_APP_KEY=...   # optional: the SaaS spans query API
mvn -q compile exec:java                                                  # BasicExample
mvn -q compile exec:java -Dexec.mainClass=codes.grow.packages.datadogapm.examples.AsyncExample
```

Against the published package, set `-Dsdk.version=<version>` (or edit `pom.xml`) instead of
installing it locally.

| Class | Shows |
| - | - |
| `BasicExample` | Agent health and version, `initTracing` (OTLP to the Agent), log correlation, propagation, error attributes, spans query |
| `AsyncExample` | `…Async` methods, combining futures, cancellation |
| `HooksAndLoggingExample` | request hooks, a logger, retries and timeouts |
