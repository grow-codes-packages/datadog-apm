// Service-owned: the Datadog APM client (API.md §1). It talks to the local Datadog Agent
// (`GET /info` on the trace intake port 8126, no authentication) and gives access to the tracing
// setup and the SaaS spans query API.

import {
  CancelledError,
  type HealthStatus,
  HttpTransport,
  type TransportOptions,
  UnsupportedError,
  healthStatus,
  probeHealth,
} from "./core/index.js";
import { DatadogApmConfig, type DatadogApmConfigOptions } from "./config.js";
import {
  initTracing,
  type DatadogInitTracingOptions,
  type DatadogTracingConfig,
  type DatadogTracingHandle,
} from "./setup.js";
import { DatadogSpansQuery } from "./spans.js";

/** Per-call options. */
export interface CallOptions {
  /** Cancels the call (`CancelledError`). */
  signal?: AbortSignal;
}

/** Test seams (undici `MockAgent`, fake sleep). Not part of the stable API. */
export type ClientInternals = Pick<TransportOptions, "dispatcher" | "sleep" | "now">;

/** The Agent's `GET /info` document (fields this package reads). */
export interface AgentInfo {
  /** Agent version, e.g. `7.85.4`. */
  version?: string;
  git_commit?: string;
  /** Trace intake endpoints the Agent serves. */
  endpoints?: string[];
  config?: Record<string, unknown>;
  [key: string]: unknown;
}

/**
 * Client for Datadog APM. `health()`, `ping()` and `serverVersion()` use the Datadog Agent's
 * `GET /info`; {@link initTracing} sets up OpenTelemetry exporting to the Agent; {@link spans}
 * is the SaaS query API (needs `apiKey` + `appKey`).
 *
 * @example
 * const client = DatadogApmClient.fromEnv();
 * const tracing = client.initTracing({ serviceName: "checkout" });
 * console.log(await client.serverVersion()); // "7.85.4"
 * await client.close(); // flushes and shuts down the tracing it started
 */
export class DatadogApmClient {
  /** The immutable configuration. */
  readonly config: DatadogApmConfig;
  readonly #transport: HttpTransport;
  readonly #internals: ClientInternals;
  readonly #handles = new Set<DatadogTracingHandle>();
  #spans: DatadogSpansQuery | undefined;
  #closed: Promise<void> | undefined;

  /** Accepts a {@link DatadogApmConfig} or plain options. */
  constructor(config: DatadogApmConfigOptions = {}, internals: ClientInternals = {}) {
    const cfg = DatadogApmConfig.from(config);
    this.config = cfg;
    this.#internals = internals;
    this.#transport = new HttpTransport({
      ...internals,
      config: cfg,
      knownSecrets: () => cfg.knownSecrets(),
    });
  }

  /** Builds a client from `GROW_DATADOG_APM_*` and `DD_*` environment variables. */
  static fromEnv(overrides: DatadogApmConfigOptions = {}): DatadogApmClient {
    return new DatadogApmClient(DatadogApmConfig.fromEnv(overrides));
  }

  /** The shared HTTP transport to the Agent. @internal */
  get transport(): HttpTransport {
    return this.#transport;
  }

  /**
   * The SaaS spans query API (created on first use, closed by {@link close}). Throws
   * `ConfigError` when `apiKey` or `appKey` is missing.
   */
  get spans(): DatadogSpansQuery {
    this.#spans ??= new DatadogSpansQuery(this.config, this.#internals);
    return this.#spans;
  }

  /** `GET /info` of the Agent. */
  async agentInfo(options: CallOptions = {}): Promise<AgentInfo> {
    this.#ensureOpen("agentInfo");
    const res = await this.#transport.get<AgentInfo | undefined>("/info", {
      operation: "agentInfo",
      ...options,
    });
    return res.data ?? {};
  }

  /**
   * Agent liveness: `GET /info`. `healthy` with status `ok` and details `version`, `git_commit`
   * when the Agent answers; `unreachable` on network errors, `unhealthy` on HTTP errors. Never
   * throws except for `CANCELLED` and `CONFIG`.
   */
  health(options: CallOptions = {}): Promise<HealthStatus> {
    return probeHealth(async () => {
      const info = await this.agentInfo(options);
      const details: Record<string, unknown> = {};
      if (info.version !== undefined) details.version = info.version;
      if (info.git_commit !== undefined) details.git_commit = info.git_commit;
      return healthStatus("ok", details, true);
    });
  }

  /**
   * Round trip to the Agent (`GET /info`), returning the latency in ms. The Agent has no
   * authentication; to check Datadog API credentials use `client.spans.ping()`.
   */
  async ping(options: CallOptions = {}): Promise<number> {
    this.#ensureOpen("ping");
    const start = performance.now();
    await this.#transport.get("/info", { operation: "ping", responseType: "none", ...options });
    return performance.now() - start;
  }

  /** Datadog Agent version from `GET /info` (e.g. `7.85.4`); `UnsupportedError` when absent. */
  async serverVersion(options: CallOptions = {}): Promise<string> {
    const info = await this.agentInfo(options);
    if (typeof info.version !== "string" || info.version === "") {
      throw new UnsupportedError("serverVersion: the Datadog Agent did not report a version");
    }
    return info.version;
  }

  /**
   * {@link initTracing} with this client's configuration as the defaults (Agent host, OTLP
   * endpoint and protocol, service/env/version, tags). {@link close} shuts the handle down.
   */
  initTracing(
    config: DatadogTracingConfig = {},
    options: Omit<DatadogInitTracingOptions, "config" | "env"> = {},
  ): DatadogTracingHandle {
    const handle = initTracing(config, { ...options, config: this.config });
    this.#handles.add(handle);
    return handle;
  }

  /** Calls on a closed client fail with `CancelledError` (same rule in every language). */
  #ensureOpen(operation: string): void {
    if (this.#transport.closed) throw new CancelledError(`${operation}: client is closed`);
  }

  /**
   * Flushes and shuts down every tracing handle started with {@link initTracing}, then releases
   * connections. Idempotent.
   */
  close(): Promise<void> {
    this.#closed ??= (async () => {
      const handles = [...this.#handles];
      this.#handles.clear();
      try {
        await Promise.all(handles.map((h) => h.shutdown()));
      } finally {
        await this.#spans?.close();
        await this.#transport.close();
      }
    })();
    return this.#closed;
  }
}
