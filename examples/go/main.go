// Command example shows the basics of the Datadog APM Go SDK: configuration
// from the environment, Agent health and version, OpenTelemetry tracing to the
// Datadog Agent (OTLP), Datadog log correlation and, when an API key and an
// application key are set, the spans query API.
//
//	DD_AGENT_HOST=127.0.0.1 DD_SERVICE=checkout DD_ENV=dev go run .
//	# optional query API: DD_API_KEY=... DD_APP_KEY=... DD_SITE=datadoghq.eu
package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"time"

	datadogapm "github.com/grow-codes-packages/datadog-apm/go"
)

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, "error:", err)
		os.Exit(1)
	}
}

func run() error {
	cfg, err := datadogapm.ConfigFromEnv()
	if err != nil {
		return err // CONFIG error naming the variable, never its value
	}
	if cfg.ServiceName == "" {
		cfg.ServiceName = "grow-datadog-apm-example"
	}
	cfg.Logger = slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelWarn}))
	fmt.Println("config:", cfg) // keys print as [REDACTED]

	client, err := datadogapm.NewClient(cfg)
	if err != nil {
		return err
	}
	defer func() { _ = client.Close() }() // flushes and shuts down the tracing below

	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	health, err := client.Health(ctx)
	if err != nil {
		return err
	}
	fmt.Printf("agent health: healthy=%t status=%s\n", health.Healthy, health.Status)
	if version, err := client.ServerVersion(ctx); err == nil {
		fmt.Println("agent version:", version)
	}

	tracing, err := client.InitTracing(ctx, datadogapm.TracingConfig{})
	if err != nil {
		return err
	}
	fmt.Println("exporting spans to", tracing.Endpoint())

	traceID, err := datadogapm.WithSpan(ctx, "checkout", func(ctx context.Context) (string, error) {
		slog.InfoContext(ctx, "processing order", "dd", datadogapm.DatadogLogFields(ctx, datadogapm.DatadogLogFieldsOptions{}))
		err := datadogapm.Traced(ctx, "charge-card", func(ctx context.Context) error {
			datadogapm.AddEvent(ctx, "card.validated", map[string]any{"attempt": 1})
			return nil
		})
		id, _ := datadogapm.CurrentTraceID(ctx)
		return id, err
	}, datadogapm.WithSpanAttributes(map[string]any{"order.id": "o-123"}))
	if err != nil {
		return err
	}
	if err := tracing.ForceFlush(ctx); err != nil {
		return err
	}
	dd, _ := datadogapm.ToDatadogTraceID(traceID)
	fmt.Printf("trace %s (Datadog trace_id %s)\n", traceID, dd.Low64Decimal)

	spans, err := client.Spans()
	if errors.Is(err, datadogapm.ErrConfig) {
		fmt.Println("query API skipped: set DD_API_KEY and DD_APP_KEY")
		return nil
	}
	if err != nil {
		return err
	}
	if _, err := spans.Ping(ctx); err != nil {
		return fmt.Errorf("check DD_API_KEY / DD_APP_KEY: %w", err)
	}
	services, err := spans.ListServices(ctx)
	if err != nil {
		return err
	}
	fmt.Println("services:", services)
	return nil
}
