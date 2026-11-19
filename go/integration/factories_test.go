package integration

import (
	"context"
	"strings"
	"testing"

	datadogapm "github.com/grow-codes-packages/datadog-apm/go"
	"github.com/grow-codes-packages/datadog-apm/go/testkit"
)

// capabilities the deployment under test offers (see conformance "requires").
// "query" and "services" are served by the trace-reader sidecar (what the real
// Datadog Agent forwarded to fakeintake), because the Datadog query API is
// SaaS-only (docs/compatibility.md).
var capabilities = []string{"version", "query", "services"}

// categories runs "core" plus this service's categories.
func categories() []string {
	return append([]string{"core"}, strings.Split("tracing", ",")...)
}

// factories builds the conformance targets: the Agent client for "core" and,
// for "tracing", this package's InitTracing (OTLP to the Agent) with the
// trace-reader as the query side.
func factories(t *testing.T) testkit.Factories {
	t.Helper()
	return testkit.Factories{
		"core": testkit.CoreFactory(func(context.Context) (testkit.CoreTarget, error) {
			return datadogapm.NewClientFromEnv()
		}),
		"tracing": testkit.TracingFactory(func(ctx context.Context, serviceName string) (testkit.TracingTarget, error) {
			h, err := datadogapm.InitTracingWithOptions(ctx, datadogapm.TracingConfig{
				ServiceName:      serviceName,
				Sampler:          datadogapm.SamplerAlwaysOn,
				DisableSetGlobal: true,
			}, datadogapm.InitTracingOptions{})
			if err != nil {
				return testkit.TracingTarget{}, err
			}
			return testkit.TracingTarget{Handle: h.TracingHandle, Query: &TraceReaderQuery{}}, nil
		}),
	}
}
