// Package datadogapm is the Grow Codes SDK for Datadog APM.
//
// Create a client from the environment (GROW_DATADOG_APM_URL,
// GROW_DATADOG_APM_TOKEN, ...) or from a Config literal:
//
//	client, err := datadogapm.NewClientFromEnv()
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
// Datadog APM is a trademark of Datadog, Inc.. This package is not affiliated
// with or endorsed by Datadog, Inc..
package datadogapm
