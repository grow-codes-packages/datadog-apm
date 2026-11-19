//! `DatadogSpansQuery` against a mock of the Datadog API (OpenAPI examples as fixtures).

mod common;

use std::time::{Duration, UNIX_EPOCH};

use futures::TryStreamExt as _;
use grow_datadog_apm as sdk;
use sdk::tracing::{DependencyOptions, TraceQueryClient, TraceSearchQuery, WaitForTraceOptions};
use sdk::*;
use serde_json::{json, Value};
use wiremock::matchers::{body_partial_json, header, method, path, query_param};
use wiremock::{Mock, MockServer, Request, Respond, ResponseTemplate};

fn fixture(name: &str) -> Value {
    let text = std::fs::read_to_string(format!(
        "{}/tests/fixtures/dd/{name}",
        env!("CARGO_MANIFEST_DIR")
    ))
    .unwrap();
    serde_json::from_str(&text).unwrap()
}

fn query(server: &MockServer) -> DatadogSpansQuery {
    query_with(server, |b| b)
}

fn query_with(
    server: &MockServer,
    f: impl FnOnce(DatadogApmConfigBuilder) -> DatadogApmConfigBuilder,
) -> DatadogSpansQuery {
    DatadogSpansQuery::new(
        f(DatadogApmConfig::builder()
            .api_url(format!("{}/", server.uri()))
            .api_key("api-key-secret-1")
            .app_key("app-key-secret-1")
            .retry(common::fast_retry(0.0)))
        .build()
        .unwrap(),
    )
    .unwrap()
}

fn span_item(
    trace: &str,
    span: &str,
    parent: &str,
    start: &str,
    service: &str,
    name: &str,
) -> Value {
    json!({"id": format!("ev-{span}"), "type": "spans", "attributes": {
        "trace_id": trace, "span_id": span, "parent_id": parent, "service": service,
        "resource_name": name, "start_timestamp": start,
        "end_timestamp": "2024-05-27T08:53:21Z"
    }})
}

#[tokio::test]
async fn search_spans_pages_and_headers() {
    let server = MockServer::start().await;
    let page1 = json!({
        "data": [span_item("42", "1", "0", "2024-05-27T08:53:20Z", "web", "GET /a")],
        "meta": {"status": "done", "request_id": "req-1", "page": {"after": "cursor-2"},
                 "warnings": [{"code": "w", "title": "partial", "detail": "d"}]}
    });
    let page2 = json!({
        "data": [span_item("42", "2", "1", "2024-05-27T08:53:20.5Z", "web", "db")],
        "meta": {"status": "done"}
    });
    Mock::given(method("POST"))
        .and(path("/api/v2/spans/events/search"))
        .and(header("DD-API-KEY", "api-key-secret-1"))
        .and(header("DD-APPLICATION-KEY", "app-key-secret-1"))
        .and(body_partial_json(
            json!({"data": {"attributes": {"page": {"cursor": "cursor-2"}}}}),
        ))
        .respond_with(ResponseTemplate::new(200).set_body_json(page2))
        .mount(&server)
        .await;
    Mock::given(method("POST"))
        .and(path("/api/v2/spans/events/search"))
        .and(body_partial_json(json!({"data": {"type": "search_request", "attributes": {
            "filter": {"query": "service:web", "from": "now-1h", "to": "now"},
            "options": {"timezone": "Europe/Berlin"}, "sort": "-timestamp", "page": {"limit": 50}}}})))
        .respond_with(ResponseTemplate::new(200).set_body_json(page1))
        .mount(&server)
        .await;
    let q = query(&server);
    let opts = SearchSpansOptions::query("service:web")
        .from("now-1h")
        .to("now")
        .limit(50)
        .sort("-timestamp")
        .timezone("Europe/Berlin");
    let page = q.search_spans_page(&opts).await.unwrap();
    assert_eq!(page.spans.len(), 1);
    assert_eq!(page.next_cursor.as_deref(), Some("cursor-2"));
    assert_eq!(page.request_id.as_deref(), Some("req-1"));
    assert_eq!(page.warnings[0].title.as_deref(), Some("partial"));
    assert_eq!(page.spans[0].id, "ev-1");
    assert_eq!(page.spans[0].raw["service"], "web");
    let all: Vec<DatadogSpan> = q.search_spans(opts.clone()).try_collect().await.unwrap();
    assert_eq!(all.len(), 2);
    assert_eq!(
        all[1].span.parent_span_id.as_deref(),
        Some("0000000000000001")
    );
    let next = q
        .search_spans_page(&opts.clone().cursor("cursor-2"))
        .await
        .unwrap();
    assert_eq!(next.status, "done");
    assert!(next.next_cursor.is_none());

    // the docs example (default options: absolute window, limit 10)
    let doc = fixture("searchSpans.docs_example.json");
    let server = MockServer::start().await;
    Mock::given(method("POST"))
        .and(path("/api/v2/spans/events/search"))
        .and(body_partial_json(json!({"data": {"attributes": {"filter": {"query": "*"}, "page": {"limit": 10}, "sort": "timestamp", "options": {"timezone": "UTC"}}}})))
        .respond_with(ResponseTemplate::new(200).set_body_json(doc["body"].clone()))
        .mount(&server)
        .await;
    let q = query(&server);
    let err = q
        .search_spans_page(&SearchSpansOptions::default())
        .await
        .unwrap_err();
    // the docs example has no ids → VALIDATION from the mapper
    assert_eq!(err.code(), ErrorCode::Validation);
    let invalid = q
        .search_spans_page(&SearchSpansOptions::default().limit(0))
        .await
        .unwrap_err();
    assert_eq!(invalid.code(), ErrorCode::Config);
    assert!(q
        .search_spans_page(&SearchSpansOptions::default().limit(1001))
        .await
        .is_err());
}

#[tokio::test]
async fn search_spans_timeout_and_errors() {
    let server = MockServer::start().await;
    Mock::given(path("/api/v2/spans/events/search"))
        .and(body_partial_json(
            json!({"data": {"attributes": {"filter": {"query": "timeout"}}}}),
        ))
        .respond_with(
            ResponseTemplate::new(200)
                .set_body_json(json!({"data": [], "meta": {"status": "timeout"}})),
        )
        .mount(&server)
        .await;
    let bad = fixture("searchSpans.400.docs_example.json");
    Mock::given(path("/api/v2/spans/events/search"))
        .and(body_partial_json(
            json!({"data": {"attributes": {"filter": {"query": "service:("}}}}),
        ))
        .respond_with(ResponseTemplate::new(400).set_body_json(bad["body"].clone()))
        .mount(&server)
        .await;
    Mock::given(path("/api/v2/spans/events/search"))
        .and(body_partial_json(
            json!({"data": {"attributes": {"filter": {"query": "garbage"}}}}),
        ))
        .respond_with(ResponseTemplate::new(200).set_body_string("{not json"))
        .mount(&server)
        .await;
    Mock::given(path("/api/v2/spans/events/search"))
        .and(body_partial_json(
            json!({"data": {"attributes": {"filter": {"query": "empty"}}}}),
        ))
        .respond_with(ResponseTemplate::new(200))
        .mount(&server)
        .await;
    Mock::given(path("/api/v2/spans/events/search"))
        .and(body_partial_json(
            json!({"data": {"attributes": {"filter": {"query": "forbidden"}}}}),
        ))
        .respond_with(ResponseTemplate::new(403).set_body_json(json!({"errors": ["Forbidden"]})))
        .mount(&server)
        .await;
    let q = query(&server);
    let err = q
        .search_spans(SearchSpansOptions::query("timeout"))
        .try_collect::<Vec<_>>()
        .await
        .unwrap_err();
    assert_eq!(err.code(), ErrorCode::Timeout);
    let err = q
        .search_spans(SearchSpansOptions::query("service:("))
        .try_collect::<Vec<_>>()
        .await
        .unwrap_err();
    assert_eq!(err.code(), ErrorCode::Validation);
    assert!(
        err.message().contains("Missing required attribute"),
        "{err}"
    );
    let err = q
        .search_spans_page(&SearchSpansOptions::query("garbage"))
        .await
        .unwrap_err();
    assert_eq!(err.code(), ErrorCode::Unavailable);
    let page = q
        .search_spans_page(&SearchSpansOptions::query("empty"))
        .await
        .unwrap();
    assert!(page.spans.is_empty() && page.status == "done");
    let err = q
        .search_spans_page(&SearchSpansOptions::query("forbidden"))
        .await
        .unwrap_err();
    assert_eq!(err.code(), ErrorCode::PermissionDenied);
    assert!(!format!("{err:?}").contains("api-key-secret-1"));
}

struct RateLimited {
    hits: std::sync::atomic::AtomicUsize,
    header: (&'static str, &'static str),
}

impl Respond for RateLimited {
    fn respond(&self, _: &Request) -> ResponseTemplate {
        if self.hits.fetch_add(1, std::sync::atomic::Ordering::SeqCst) == 0 {
            ResponseTemplate::new(429).insert_header(self.header.0, self.header.1)
        } else {
            ResponseTemplate::new(200).set_body_json(json!({"status": "ok"}))
        }
    }
}

#[tokio::test]
async fn rate_limits_wait_for_the_reset() {
    for h in [("x-ratelimit-reset", "0"), ("retry-after", "0")] {
        let server = MockServer::start().await;
        Mock::given(path("/api/v2/validate_keys"))
            .respond_with(RateLimited {
                hits: Default::default(),
                header: h,
            })
            .mount(&server)
            .await;
        query(&server).ping().await.unwrap();
        assert_eq!(server.received_requests().await.unwrap().len(), 2, "{h:?}");
    }
    // without a hint: backoff; exhausted retries → RATE_LIMITED with the request id
    let server = MockServer::start().await;
    Mock::given(path("/api/v2/validate_keys"))
        .respond_with(ResponseTemplate::new(429).insert_header("x-request-id", "rid-9"))
        .mount(&server)
        .await;
    let err = query_with(&server, |b| b.max_retries(1))
        .ping()
        .await
        .unwrap_err();
    assert_eq!(err.code(), ErrorCode::RateLimited);
    assert_eq!(err.request_id(), Some("rid-9"));
    assert_eq!(server.received_requests().await.unwrap().len(), 2);
    // a reset beyond max_retry_after is returned at once with retry_after
    let server = MockServer::start().await;
    Mock::given(path("/api/v2/validate_keys"))
        .respond_with(ResponseTemplate::new(429).insert_header("x-ratelimit-reset", "3600"))
        .mount(&server)
        .await;
    let err = query(&server).ping().await.unwrap_err();
    assert_eq!(err.retry_after(), Some(Duration::from_secs(3600)));
    assert_eq!(server.received_requests().await.unwrap().len(), 1);
}

#[tokio::test]
async fn ping_maps_invalid_keys_to_authentication() {
    let server = MockServer::start().await;
    let ok = fixture("ping.validate_keys.docs_example.json");
    Mock::given(path("/api/v2/validate_keys"))
        .and(header("DD-APPLICATION-KEY", "app-key-secret-1"))
        .respond_with(ResponseTemplate::new(200).set_body_json(ok["body"].clone()))
        .mount(&server)
        .await;
    assert!(query(&server).ping().await.unwrap() > Duration::ZERO);
    for (status, code) in [
        (403, ErrorCode::Authentication),
        (401, ErrorCode::Authentication),
        (500, ErrorCode::Unavailable),
    ] {
        let server = MockServer::start().await;
        Mock::given(path("/api/v2/validate_keys"))
            .respond_with(
                ResponseTemplate::new(status)
                    .insert_header("x-request-id", "rid-1")
                    .set_body_json(json!({"errors": ["Forbidden"]})),
            )
            .mount(&server)
            .await;
        let err = query_with(&server, |b| b.max_retries(0))
            .ping()
            .await
            .unwrap_err();
        assert_eq!(err.code(), code, "{status}");
        assert!(!err.is_retryable() || status == 500);
        if status == 403 {
            assert_eq!(err.http_status(), Some(403));
            assert_eq!(err.request_id(), Some("rid-1"));
        }
    }
}

#[tokio::test]
async fn aggregate_and_list_operations() {
    let server = MockServer::start().await;
    let doc = fixture("aggregateSpans.docs_example.json");
    Mock::given(method("POST"))
        .and(path("/api/v2/spans/analytics/aggregate"))
        .and(body_partial_json(json!({"data": {"type": "aggregate_request", "attributes": {
            "compute": [{"aggregation": "pc90", "metric": "@duration", "type": "timeseries", "interval": "5m"}],
            "group_by": [{"facet": "service", "limit": 20}],
            "filter": {"query": "service:web*"}}}})))
        .respond_with(ResponseTemplate::new(200).set_body_json(doc["body"].clone()))
        .mount(&server)
        .await;
    Mock::given(method("POST"))
        .and(path("/api/v2/spans/analytics/aggregate"))
        .and(body_partial_json(json!({"data": {"attributes": {"filter": {"query": "service:\"my svc\""},
            "group_by": [{"facet": "resource_name", "limit": 1000}]}}})))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "data": [
                {"attributes": {"by": {"resource_name": "GET /b"}, "compute": {"c0": 3}}},
                {"attributes": {"by": {"resource_name": "GET /a"}, "compute": {"c0": 1}}},
                {"attributes": {"by": {"resource_name": "GET /a"}, "compute": {"c0": [{"time": "t1", "value": 2.5}, {}], "c1": "x"}}},
                {"attributes": {"by": {}, "compute": {}}}
            ],
            "meta": {"status": "done", "request_id": "agg-1", "warnings": [{"code": "c"}]}
        })))
        .mount(&server)
        .await;
    let q = query(&server);
    let r = q
        .aggregate_spans(
            &[SpanCompute::new(aggregation::PC90)
                .metric("@duration")
                .timeseries("5m")],
            &[SpanGroupBy::facet("service").limit(20)],
            &AggregateSpansOptions {
                query: Some("service:web*".into()),
                from: Some("now-15m".into()),
                to: Some("now".into()),
                timezone: Some("GMT".into()),
            },
        )
        .await
        .unwrap();
    assert_eq!(r.buckets.len(), 1);
    assert_eq!(r.buckets[0].by["host"], "my-hostname");
    assert_eq!(r.buckets[0].computes["c0"].as_f64(), Some(19.0));
    assert_eq!(r.status, "done");
    assert!(r.request_id.is_none() && r.warnings.is_empty());

    let ops = q.list_operations("my svc").await.unwrap();
    assert_eq!(ops, vec!["GET /a", "GET /b"]);
    let r = q
        .aggregate_spans(
            &[SpanCompute::new(aggregation::COUNT)],
            &[SpanGroupBy::facet("resource_name").limit(1000)],
            &AggregateSpansOptions {
                query: Some("service:\"my svc\"".into()),
                ..Default::default()
            },
        )
        .await
        .unwrap();
    assert_eq!(r.request_id.as_deref(), Some("agg-1"));
    assert_eq!(r.warnings.len(), 1);
    match &r.buckets[2].computes["c0"] {
        SpanComputeValue::Timeseries(points) => {
            assert_eq!(points[0].time, "t1");
            assert_eq!(points[0].value, 2.5);
            assert_eq!(points[1].value, 0.0);
        }
        other => panic!("{other:?}"),
    }
    assert!(matches!(
        r.buckets[2].computes["c1"],
        SpanComputeValue::Other(_)
    ));
    assert_eq!(r.buckets[2].computes["c1"].as_f64(), None);
    let json = serde_json::to_value(&r.buckets[2].computes).unwrap();
    assert_eq!(json["c0"][0]["value"], 2.5);
    assert_eq!(
        q.aggregate_spans(&[], &[], &Default::default())
            .await
            .unwrap_err()
            .code(),
        ErrorCode::Config
    );
    // serialization of the optional request parts
    let g = SpanGroupBy {
        facet: "@http.status_code".into(),
        sort: Some(SpanGroupBySort {
            aggregation: Some("count".into()),
            order: Some("desc".into()),
            sort_type: Some("measure".into()),
            metric: None,
        }),
        missing: Some(json!("none")),
        total: Some(json!(true)),
        histogram: Some(SpanHistogram {
            interval: 10.0,
            min: 0.0,
            max: 100.0,
        }),
        limit: None,
    };
    let v = serde_json::to_value(&g).unwrap();
    assert_eq!(v["sort"]["type"], "measure");
    assert_eq!(v["histogram"]["max"], 100.0);
    assert!(v.get("limit").is_none());
}

#[tokio::test]
async fn get_trace_search_and_preview() {
    let server = MockServer::start().await;
    let trace = "a3ce929d0e0e47364bf92f3577b34da6";
    Mock::given(path("/api/v2/spans/events/search"))
        .and(body_partial_json(json!({"data": {"attributes": {"filter": {"query": format!("trace_id:{trace}")}, "page": {"limit": 1000}}}})))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({"data": [
            span_item(trace, "2", "1", "2024-05-27T08:53:20.5Z", "web", "child"),
            span_item(trace, "1", "0", "2024-05-27T08:53:20Z", "web", "root"),
            span_item(trace, "1", "0", "2024-05-27T08:53:20Z", "web", "root"),
        ]})))
        .mount(&server)
        .await;
    Mock::given(path("/api/v2/spans/events/search"))
        .and(body_partial_json(
            json!({"data": {"attributes": {"filter": {"query": "trace_id:42"}}}}),
        ))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({"data": []})))
        .mount(&server)
        .await;
    let doc = fixture("getTraceById.docs_example.json");
    Mock::given(method("GET"))
        .and(path("/api/v2/trace/0000000000000000abc1230000000000"))
        .respond_with(ResponseTemplate::new(200).set_body_json(doc["body"].clone()))
        .mount(&server)
        .await;
    Mock::given(path("/api/v2/trace/9007199254740993"))
        .respond_with(ResponseTemplate::new(404).set_body_json(json!({"errors": ["Not found"]})))
        .mount(&server)
        .await;
    Mock::given(path("/api/v2/trace/00000000000000000000000000000abc"))
        .respond_with(
            ResponseTemplate::new(200)
                .set_body_json(json!({"data": {"attributes": {"spans": []}}})),
        )
        .mount(&server)
        .await;
    Mock::given(path("/api/v2/trace/00000000000000000000000000000abd"))
        .respond_with(ResponseTemplate::new(200))
        .mount(&server)
        .await;
    Mock::given(path("/api/v2/trace/00000000000000000000000000000abe"))
        .respond_with(
            ResponseTemplate::new(200)
                .set_body_json(json!({"data": {"attributes": {"spans": [{"traceID": "x"}]}}})),
        )
        .mount(&server)
        .await;
    let q = query(&server);
    let t = q.get_trace(trace).await.unwrap().unwrap();
    assert_eq!(t.trace_id, trace);
    assert_eq!(t.spans.len(), 2, "merged by span id");
    assert_eq!(t.spans[0].name, "root");
    assert!(q.get_trace("2a").await.unwrap().is_none());
    let w = q
        .wait_for_trace(
            trace,
            WaitForTraceOptions::default().timeout(Duration::from_secs(5)),
        )
        .await
        .unwrap();
    assert_eq!(w.spans.len(), 2);
    let err = q
        .wait_for_trace(
            "2a",
            WaitForTraceOptions::default()
                .timeout(Duration::from_millis(50))
                .interval(Duration::from_millis(10)),
        )
        .await
        .unwrap_err();
    assert_eq!(err.code(), ErrorCode::Timeout);
    assert_eq!(
        q.get_trace("xyz").await.unwrap_err().code(),
        ErrorCode::Validation
    );

    let preview = GetTraceOptions {
        preview: true,
        ..Default::default()
    };
    let t = q
        .get_trace_with_options("0000000000000000abc1230000000000", &preview)
        .await
        .unwrap()
        .unwrap();
    assert_eq!(t.spans[0].name, "GET /products");
    assert_eq!(t.spans[0].service_name, "web-store");
    for missing in ["00000000000000000020000000000001", "abc", "abd"] {
        assert!(
            q.get_trace_with_options(missing, &preview)
                .await
                .unwrap()
                .is_none(),
            "{missing}"
        );
    }
    assert_eq!(
        q.get_trace_with_options("abe", &preview)
            .await
            .unwrap_err()
            .code(),
        ErrorCode::Validation
    );
    let window = GetTraceOptions {
        from: Some(DatadogTime::At(
            UNIX_EPOCH + Duration::from_secs(1_716_800_000),
        )),
        to: Some(DatadogTime::Expr("now".into())),
        preview: false,
    };
    assert!(q
        .get_trace_with_options(trace, &window)
        .await
        .unwrap()
        .is_some());
}

#[tokio::test]
async fn list_services_search_traces_and_dependencies() {
    let server = MockServer::start().await;
    let doc = fixture("listServices.docs_example.json");
    Mock::given(path("/api/v2/apm/services"))
        .and(query_param("filter[env]", "prod"))
        .respond_with(ResponseTemplate::new(200).set_body_json(doc["body"].clone()))
        .mount(&server)
        .await;
    Mock::given(path("/api/v2/apm/services"))
        .and(query_param("filter[env]", "*"))
        .respond_with(
            ResponseTemplate::new(200)
                .set_body_json(json!({"data": {"attributes": {"services": ["z", "a", 3]}}})),
        )
        .mount(&server)
        .await;
    Mock::given(path("/api/v2/apm/services"))
        .and(query_param("filter[env]", "empty"))
        .respond_with(ResponseTemplate::new(200))
        .mount(&server)
        .await;
    Mock::given(path("/api/v2/spans/events/search"))
        .and(body_partial_json(json!({"data": {"attributes": {"page": {"cursor": "p2"}}}})))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({"data": [
            span_item("0000000000000000000000000000000b", "5", "0", "2024-05-27T08:53:19Z", "web", "late-root"),
        ]})))
        .mount(&server)
        .await;
    Mock::given(path("/api/v2/spans/events/search"))
        .and(body_partial_json(json!({"data": {"attributes": {
            "filter": {"query": "service:web resource_name:\"GET /a\" @grow.run:r1 @duration:>=1000000 @duration:<=2000000000"},
            "sort": "-timestamp"}}})))
        .respond_with(ResponseTemplate::new(200).set_body_json(json!({
            "data": [
                span_item("0000000000000000000000000000000a", "2", "1", "2024-05-27T08:53:20.5Z", "web", "child"),
                span_item("0000000000000000000000000000000a", "1", "0", "2024-05-27T08:53:20Z", "web", "GET /a"),
                span_item("0000000000000000000000000000000c", "9", "8", "2024-05-27T08:53:20Z", "web", "orphan"),
            ],
            "meta": {"page": {"after": "p2"}}
        })))
        .mount(&server)
        .await;
    let q = query_with(&server, |b| b.environment("prod"));
    assert_eq!(q.list_services().await.unwrap(), vec!["test-service"]);
    let all = q
        .list_services_with_options(&ListServicesOptions {
            env: Some("*".into()),
        })
        .await
        .unwrap();
    assert_eq!(all, vec!["a", "z"]);
    assert!(q
        .list_services_with_options(&ListServicesOptions {
            env: Some("empty".into())
        })
        .await
        .unwrap()
        .is_empty());
    let mut tq = TraceSearchQuery::service("web")
        .operation("GET /a")
        .tag("grow.run", "r1")
        .limit(10);
    tq.min_duration = Some(Duration::from_millis(1));
    tq.max_duration = Some(Duration::from_secs(2));
    tq.start = Some(UNIX_EPOCH + Duration::from_secs(1_716_799_000));
    tq.end = Some(UNIX_EPOCH + Duration::from_secs(1_716_801_000));
    assert_eq!(
        trace_search_query_string(&tq),
        "service:web resource_name:\"GET /a\" @grow.run:r1 @duration:>=1000000 @duration:<=2000000000"
    );
    let found = q.search_traces(&tq).await.unwrap();
    assert_eq!(found.len(), 3);
    assert_eq!(found[0].trace_id, "0000000000000000000000000000000a");
    assert_eq!(found[0].span_count, 2);
    assert_eq!(found[0].root_name.as_deref(), Some("GET /a"));
    assert_eq!(found[0].root_service_name.as_deref(), Some("web"));
    assert_eq!(found[1].root_name.as_deref(), Some("orphan"));
    assert_eq!(found[2].root_name.as_deref(), Some("late-root"));
    // limit 1: only the first trace, no second page
    let found = q.search_traces(&tq.clone().limit(1)).await.unwrap();
    assert_eq!(found.len(), 1);
    let mut zero = tq.clone();
    zero.limit = 0;
    assert_eq!(q.search_traces(&zero).await.unwrap().len(), 3);
    assert_eq!(trace_search_query_string(&TraceSearchQuery::default()), "*");
    assert_eq!(quote_query_value(""), "\"\"");
    assert_eq!(quote_query_value("a\"b\\c"), "\"a\\\"b\\\\c\"");
    assert_eq!(
        q.get_dependencies(DependencyOptions::default())
            .await
            .unwrap_err()
            .code(),
        ErrorCode::Unsupported
    );
    q.close();
    q.close();
    assert_eq!(
        q.list_services().await.unwrap_err().code(),
        ErrorCode::Cancelled
    );
}

#[tokio::test]
async fn constructors() {
    assert_eq!(
        DatadogSpansQuery::new(DatadogApmConfig::builder().app_key("a").build().unwrap())
            .unwrap_err()
            .code(),
        ErrorCode::Config
    );
    let q = DatadogSpansQuery::new(
        DatadogApmConfig::builder()
            .api_key("k")
            .app_key("a")
            .build()
            .unwrap(),
    )
    .unwrap();
    assert_eq!(q.config().api_url(), "https://api.datadoghq.com");
    let _ = DatadogSpansQuery::from_env();
    assert_eq!(DATADOG_WAIT_INTERVAL, Duration::from_secs(2));
}
