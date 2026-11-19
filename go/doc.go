// Package datadogapm is the Grow Codes SDK for Datadog APM.
//
// Tracing uses the official OpenTelemetry SDK and exports OTLP to the local
// Datadog Agent (HTTP/protobuf on port 4318 by default, or gRPC on 4317), with
// unified service tagging, the Datadog (x-datadog-*) and W3C propagators,
// Datadog error attributes and log correlation helpers:
//
//	h, err := datadogapm.InitTracing(ctx, datadogapm.TracingConfig{ServiceName: "checkout", Environment: "prod"})
//	if err != nil {
//		return err
//	}
//	defer h.Shutdown(context.Background())
//	_ = datadogapm.Traced(ctx, "charge", func(ctx context.Context) error {
//		slog.InfoContext(ctx, "charging", "dd", datadogapm.DatadogLogFields(ctx, datadogapm.DatadogLogFieldsOptions{}))
//		return nil
//	})
//
// The Client checks the Agent (GET /info on port 8126) and opens the Datadog
// SaaS spans query API (DatadogSpansQuery: SearchSpans, AggregateSpans,
// GetTrace, ListServices, …), which needs an API key and an application key
// with apm_read:
//
//	client, err := datadogapm.NewClientFromEnv() // DD_AGENT_HOST, DD_API_KEY, DD_APP_KEY, DD_SITE, GROW_DATADOG_APM_*
//	if err != nil {
//		return err
//	}
//	defer client.Close()
//	status, err := client.Health(ctx)
//
// Every error is an *Error; compare codes with errors.Is against the Err*
// sentinels (errors.Is(err, datadogapm.ErrNotFound)). Credentials are wrapped in
// Secret and never appear in errors, hooks or logs.
//
// Datadog is a trademark of Datadog, Inc. This package is not affiliated
// with or endorsed by Datadog, Inc.
package datadogapm
