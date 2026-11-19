package integration

import (
	"bufio"
	"os"
	"strings"
	"testing"
)

// TestMain loads /bootstrap/env (KEY=value lines written by the compose
// bootstrap job) into the process environment for every test; variables that
// are already set win.
func TestMain(m *testing.M) {
	path := os.Getenv("GROW_BOOTSTRAP_ENV")
	if path == "" {
		path = "/bootstrap/env"
	}
	if f, err := os.Open(path); err == nil {
		sc := bufio.NewScanner(f)
		for sc.Scan() {
			line := strings.TrimSpace(sc.Text())
			if line == "" || strings.HasPrefix(line, "#") {
				continue
			}
			key, value, ok := strings.Cut(line, "=")
			if !ok {
				continue
			}
			key = strings.TrimSpace(strings.TrimPrefix(key, "export "))
			if _, set := os.LookupEnv(key); !set {
				_ = os.Setenv(key, strings.Trim(strings.TrimSpace(value), `"'`))
			}
		}
		_ = f.Close()
	}
	os.Exit(m.Run())
}

// runID is the unique id of this run (set by the compose harness).
func runID() string {
	if v := os.Getenv("GROW_TEST_RUN_ID"); v != "" {
		return v
	}
	return "local"
}

// requireService skips when the Docker harness is not running.
func requireService(t *testing.T) {
	t.Helper()
	if os.Getenv("GROW_DATADOG_APM_URL") == "" || os.Getenv("TRACE_READER_URL") == "" {
		t.Skip("GROW_DATADOG_APM_URL / TRACE_READER_URL not set; run through docker/compose.yml")
	}
}
