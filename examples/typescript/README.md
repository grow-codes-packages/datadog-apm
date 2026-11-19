# TypeScript example

Agent health, OTLP tracing to the Datadog Agent, Datadog header propagation, log correlation and —
with an API key and an application key — reading the trace back from the Datadog spans API.

```sh
(cd ../../typescript && npm install && npm run build)
npm install
DD_AGENT_HOST=127.0.0.1 DD_SERVICE=example-checkout DD_ENV=dev npm start
# optional: DD_API_KEY=... DD_APP_KEY=... DD_SITE=datadoghq.eu
```

The Datadog Agent must have its OTLP receiver enabled
(`DD_OTLP_CONFIG_RECEIVER_PROTOCOLS_HTTP_ENDPOINT=0.0.0.0:4318`).

Replace the `file:` dependency in `package.json` with a version (`npm install @grow-codes/datadog-apm`)
to run it against the published package.
