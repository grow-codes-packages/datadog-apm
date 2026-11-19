// Service-owned: configuration of the Datadog APM client (API.md §0, DECISIONS.md #1).

import {
  type CommonConfig,
  type CommonConfigOptions,
  type CommonVendorEnv,
  ConfigError,
  type EnvMap,
  EnvReader,
  type Hooks,
  type ClientLogger,
  REDACTED,
  type RetryPolicy,
  Secret,
  type TlsOptions,
  describeCommonConfig,
  mergeOptions,
  readCommonEnv,
  redactUrl,
  resolveCommonConfig,
  secretValues,
  validateCommonConfig,
} from "./core/index.js";
import { DEFAULT_SITE, validateSite } from "./sites.js";

const INSPECT = Symbol.for("nodejs.util.inspect.custom");

/** OTLP export protocols the Datadog Agent accepts. */
export type DatadogOtlpProtocol = "http/protobuf" | "grpc";

const OTLP_PROTOCOLS: readonly string[] = ["http/protobuf", "grpc"];

/** Default Agent OTLP/HTTP port. */
export const OTLP_HTTP_PORT = 4318;
/** Default Agent OTLP/gRPC port. */
export const OTLP_GRPC_PORT = 4317;
/** Default Agent trace intake port (`/info`). */
export const AGENT_TRACE_PORT = 8126;

/** Options accepted by {@link DatadogApmConfig} (all optional). */
export interface DatadogApmConfigOptions extends CommonConfigOptions {
  /**
   * Datadog Agent trace intake base URL, used by `health()`, `ping()` and `serverVersion()`
   * (`GET /info`). Default `http://127.0.0.1:8126`.
   */
  url?: string;
  /**
   * OTLP endpoint of the Agent. HTTP: a base URL (`http://agent:4318`, `/v1/traces` is appended
   * when the URL has no path) or the full traces URL; gRPC: `http://agent:4317`. Default: the host
   * of {@link url} with port 4318 (HTTP) or 4317 (gRPC).
   */
  otlpEndpoint?: string;
  /** OTLP protocol. Default `http/protobuf`. */
  protocol?: DatadogOtlpProtocol;
  /** Unified service tagging: `service` (resource `service.name`). */
  serviceName?: string;
  /** Unified service tagging: `version` (resource `service.version`). */
  serviceVersion?: string;
  /** Unified service tagging: `env` (resource `deployment.environment.name`). */
  environment?: string;
  /** Extra resource attributes (`DD_TAGS`). */
  tags?: Record<string, string>;
  /** Datadog API key: needed only by the query API ({@link DatadogSpansQuery}). */
  apiKey?: string | Secret;
  /** Datadog application key with `apm_read`: needed only by the query API. */
  appKey?: string | Secret;
  /** Datadog site, e.g. `datadoghq.com` (default) or `datadoghq.eu`. */
  site?: string;
  /** Query API base URL. Default `https://api.<site>`. */
  apiUrl?: string;
}

/**
 * Vendor environment variables read after `GROW_DATADOG_APM_<NAME>`, in order. `url` falls back
 * to `http://${DD_AGENT_HOST}:${DD_TRACE_AGENT_PORT}` when none of them is set.
 */
export const VENDOR_ENV: CommonVendorEnv & {
  serviceName: readonly string[];
  serviceVersion: readonly string[];
  environment: readonly string[];
  tags: readonly string[];
  apiKey: readonly string[];
  appKey: readonly string[];
  site: readonly string[];
  protocol: readonly string[];
} = {
  url: ["DD_TRACE_AGENT_URL"],
  serviceName: ["DD_SERVICE", "OTEL_SERVICE_NAME"],
  serviceVersion: ["DD_VERSION"],
  environment: ["DD_ENV"],
  tags: ["DD_TAGS"],
  apiKey: ["DD_API_KEY"],
  appKey: ["DD_APP_KEY", "DD_APPLICATION_KEY"],
  site: ["DD_SITE"],
  protocol: ["OTEL_EXPORTER_OTLP_TRACES_PROTOCOL", "OTEL_EXPORTER_OTLP_PROTOCOL"],
};

/**
 * Parses Datadog tags (`DD_TAGS`): `key:value` pairs separated by commas and/or spaces. Entries
 * without a `:` or with an empty key are ignored.
 *
 * @example
 * parseDatadogTags("team:sdk, tier:web") // { team: "sdk", tier: "web" }
 */
export function parseDatadogTags(raw: string): Record<string, string> {
  const out: Record<string, string> = {};
  for (const part of raw.split(/[\s,]+/)) {
    const colon = part.indexOf(":");
    if (colon <= 0) continue;
    out[part.slice(0, colon)] = part.slice(colon + 1);
  }
  return out;
}

function isHttpUrl(value: string): boolean {
  try {
    const u = new URL(value);
    return u.protocol === "http:" || u.protocol === "https:";
  } catch {
    return false;
  }
}

function hasPath(url: string): boolean {
  const path = new URL(url).pathname;
  return path !== "" && path !== "/";
}

/**
 * The exporter URL for a protocol and an OTLP endpoint: for `http/protobuf` an endpoint without a
 * path gets `/v1/traces`; gRPC endpoints are used as they are.
 */
export function otlpTracesUrl(protocol: DatadogOtlpProtocol, endpoint: string): string {
  if (protocol === "grpc" || hasPath(endpoint)) return endpoint;
  return `${endpoint.replace(/\/+$/, "")}/v1/traces`;
}

function firstEnv(
  env: EnvMap,
  names: readonly string[],
): { name: string; value: string } | undefined {
  for (const name of names) {
    const value = env[name];
    if (value !== undefined && value.trim() !== "") return { name, value };
  }
  return undefined;
}

function hostUrl(host: string, port: number): string {
  const h = host.includes(":") && !host.startsWith("[") ? `[${host}]` : host;
  return `http://${h}:${port}`;
}

/** Immutable configuration of {@link DatadogApmClient} and {@link initTracing}. */
export class DatadogApmConfig implements CommonConfig {
  /** Default Agent trace intake URL. */
  static readonly DEFAULT_URL = `http://127.0.0.1:${AGENT_TRACE_PORT}`;
  /** Prefix of every environment variable. */
  static readonly ENV_PREFIX = "GROW_DATADOG_APM_";

  readonly url: string;
  readonly connectTimeoutMs: number;
  readonly requestTimeoutMs: number;
  readonly totalTimeoutMs: number;
  readonly retry: RetryPolicy;
  readonly tls: Readonly<TlsOptions>;
  readonly proxy?: string;
  readonly headers: Readonly<Record<string, string>>;
  readonly userAgentSuffix?: string;
  readonly hooks?: Hooks;
  readonly logger?: ClientLogger;
  /** OTLP endpoint as configured (see {@link otlpTracesEndpoint} for the resolved URL). */
  readonly otlpEndpoint?: string;
  /** OTLP protocol. */
  readonly protocol: DatadogOtlpProtocol;
  /** `service` of unified service tagging. */
  readonly serviceName?: string;
  /** `version` of unified service tagging. */
  readonly serviceVersion?: string;
  /** `env` of unified service tagging. */
  readonly environment?: string;
  /** Extra resource attributes. */
  readonly tags: Readonly<Record<string, string>>;
  /** Datadog API key (query API only). */
  readonly apiKey?: Secret;
  /** Datadog application key (query API only). */
  readonly appKey?: Secret;
  /** Datadog site. */
  readonly site: string;
  /** Query API base URL. */
  readonly apiUrl: string;

  /** Applies defaults, validates (throws {@link ConfigError}) and freezes. */
  constructor(options: DatadogApmConfigOptions = {}) {
    const common = resolveCommonConfig(options, DatadogApmConfig.DEFAULT_URL);
    this.url = common.url;
    this.connectTimeoutMs = common.connectTimeoutMs;
    this.requestTimeoutMs = common.requestTimeoutMs;
    this.totalTimeoutMs = common.totalTimeoutMs;
    this.retry = common.retry;
    this.tls = common.tls;
    this.headers = common.headers;
    if (common.proxy !== undefined) this.proxy = common.proxy;
    if (common.userAgentSuffix !== undefined) this.userAgentSuffix = common.userAgentSuffix;
    if (common.hooks !== undefined) this.hooks = common.hooks;
    if (common.logger !== undefined) this.logger = common.logger;
    if (options.otlpEndpoint !== undefined) this.otlpEndpoint = options.otlpEndpoint;
    this.protocol = options.protocol ?? "http/protobuf";
    if (options.serviceName !== undefined) this.serviceName = options.serviceName;
    if (options.serviceVersion !== undefined) this.serviceVersion = options.serviceVersion;
    if (options.environment !== undefined) this.environment = options.environment;
    this.tags = Object.freeze({ ...options.tags });
    const apiKey = Secret.from(options.apiKey);
    if (apiKey !== undefined) this.apiKey = apiKey;
    const appKey = Secret.from(options.appKey);
    if (appKey !== undefined) this.appKey = appKey;
    this.site = (options.site ?? DEFAULT_SITE).trim().toLowerCase();
    this.apiUrl = options.apiUrl ?? `https://api.${this.site}`;
    this.validate();
    Object.freeze(this);
  }

  /** Returns `options` when it already is a config, otherwise builds one. */
  static from(options: DatadogApmConfigOptions = {}): DatadogApmConfig {
    if (options instanceof DatadogApmConfig) return options;
    return new DatadogApmConfig(options);
  }

  /**
   * Builds a config from the environment. Precedence per field: explicit `overrides` >
   * `GROW_DATADOG_APM_<NAME>` > vendor variables > defaults.
   *
   * | Field | `GROW_DATADOG_APM_*` | Vendor variables |
   * | - | - | - |
   * | `url` | `URL` | `DD_TRACE_AGENT_URL`, else `http://${DD_AGENT_HOST}:${DD_TRACE_AGENT_PORT}` |
   * | `otlpEndpoint` | `OTLP_HTTP_ENDPOINT` / `OTLP_GRPC_ENDPOINT` (by protocol), `OTLP_ENDPOINT` | `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`, `OTEL_EXPORTER_OTLP_ENDPOINT` |
   * | `protocol` | `OTLP_PROTOCOL` | `OTEL_EXPORTER_OTLP_TRACES_PROTOCOL`, `OTEL_EXPORTER_OTLP_PROTOCOL` |
   * | `serviceName` | `SERVICE` | `DD_SERVICE`, `OTEL_SERVICE_NAME` |
   * | `serviceVersion` | `VERSION` | `DD_VERSION` |
   * | `environment` | `ENV` | `DD_ENV` |
   * | `tags` | `TAGS` | `DD_TAGS` |
   * | `apiKey` | `API_KEY` | `DD_API_KEY` |
   * | `appKey` | `APP_KEY` | `DD_APP_KEY`, `DD_APPLICATION_KEY` |
   * | `site` | `SITE` | `DD_SITE` |
   * | `apiUrl` | `API_URL` | — |
   */
  static fromEnv(
    overrides: DatadogApmConfigOptions = {},
    env: EnvMap = process.env,
  ): DatadogApmConfig {
    const reader = new EnvReader(DatadogApmConfig.ENV_PREFIX, env);
    const fromEnv: DatadogApmConfigOptions = readCommonEnv(reader, VENDOR_ENV);
    if (fromEnv.url === undefined) {
      const host = firstEnv(env, ["DD_AGENT_HOST"])?.value.trim();
      const portHit = firstEnv(env, ["DD_TRACE_AGENT_PORT"]);
      let port: number | undefined;
      if (portHit !== undefined) {
        port = Number(portHit.value.trim());
        if (!/^\d+$/.test(portHit.value.trim()) || port < 1 || port > 65535)
          throw new ConfigError(
            `invalid value in environment variable ${portHit.name}: expected a port number`,
          );
      }
      if (host !== undefined || port !== undefined) {
        fromEnv.url = hostUrl(host ?? "127.0.0.1", port ?? AGENT_TRACE_PORT);
      }
    }
    const protocolHit = reader.lookup("OTLP_PROTOCOL", VENDOR_ENV.protocol);
    if (protocolHit !== undefined) {
      const value = protocolHit.value.trim().toLowerCase();
      if (value !== "grpc" && value !== "http/protobuf") {
        throw new ConfigError(
          `invalid value in environment variable ${protocolHit.name}: expected grpc or http/protobuf`,
        );
      }
      fromEnv.protocol = value;
    }
    const protocol = overrides.protocol ?? fromEnv.protocol ?? "http/protobuf";
    const specific = protocol === "grpc" ? "OTLP_GRPC_ENDPOINT" : "OTLP_HTTP_ENDPOINT";
    const endpoint = reader.lookup(specific, [
      `${DatadogApmConfig.ENV_PREFIX}OTLP_ENDPOINT`,
      "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT",
    ]);
    if (endpoint !== undefined) {
      fromEnv.otlpEndpoint = endpoint.value;
    } else {
      const base = firstEnv(env, ["OTEL_EXPORTER_OTLP_ENDPOINT"]);
      if (base !== undefined) {
        fromEnv.otlpEndpoint =
          protocol === "grpc" ? base.value : `${base.value.replace(/\/+$/, "")}/v1/traces`;
      }
    }
    const strings = [
      ["SERVICE", VENDOR_ENV.serviceName, "serviceName"],
      ["VERSION", VENDOR_ENV.serviceVersion, "serviceVersion"],
      ["ENV", VENDOR_ENV.environment, "environment"],
      ["SITE", VENDOR_ENV.site, "site"],
      ["API_URL", [], "apiUrl"],
    ] as const;
    for (const [name, vendor, field] of strings) {
      const value = reader.string(name, vendor);
      if (value !== undefined) fromEnv[field] = value.trim();
    }
    const tags = reader.string("TAGS", VENDOR_ENV.tags);
    if (tags !== undefined) fromEnv.tags = parseDatadogTags(tags);
    const apiKey = reader.secret("API_KEY", VENDOR_ENV.apiKey);
    if (apiKey !== undefined) fromEnv.apiKey = apiKey;
    const appKey = reader.secret("APP_KEY", VENDOR_ENV.appKey);
    if (appKey !== undefined) fromEnv.appKey = appKey;
    const merged = mergeOptions<DatadogApmConfigOptions>(fromEnv, overrides);
    if (fromEnv.tags !== undefined && overrides.tags !== undefined) {
      merged.tags = { ...fromEnv.tags, ...overrides.tags };
    }
    return new DatadogApmConfig(merged);
  }

  /** The resolved OTLP traces URL for {@link protocol} (see {@link otlpTracesUrl}). */
  get otlpTracesEndpoint(): string {
    if (this.otlpEndpoint !== undefined) return otlpTracesUrl(this.protocol, this.otlpEndpoint);
    const agent = new URL(this.url);
    agent.port = String(this.protocol === "grpc" ? OTLP_GRPC_PORT : OTLP_HTTP_PORT);
    return otlpTracesUrl(this.protocol, `${agent.protocol}//${agent.host}`);
  }

  /** True when both the API key and the application key are set (query API usable). */
  get hasQueryCredentials(): boolean {
    return this.apiKey !== undefined && this.appKey !== undefined;
  }

  /** Throws {@link ConfigError} when a setting is invalid. */
  validate(): void {
    validateCommonConfig(this);
    const problems: string[] = [];
    if (!OTLP_PROTOCOLS.includes(this.protocol))
      problems.push("protocol must be http/protobuf or grpc");
    if (this.otlpEndpoint !== undefined && !isHttpUrl(this.otlpEndpoint))
      problems.push("otlpEndpoint must be an http or https URL");
    if (!isHttpUrl(this.apiUrl)) problems.push("apiUrl must be an http or https URL");
    for (const key of ["serviceName", "serviceVersion", "environment"] as const) {
      if (this[key]?.trim() === "") problems.push(`${key} must not be blank`);
    }
    if (this.apiKey?.reveal().trim() === "") problems.push("apiKey must not be blank");
    if (this.appKey?.reveal().trim() === "") problems.push("appKey must not be blank");
    if (problems.length > 0) throw new ConfigError(`invalid configuration: ${problems.join("; ")}`);
    validateSite(this.site);
  }

  /** Credential values to redact from error messages and logs. */
  knownSecrets(): string[] {
    return secretValues(this.apiKey, this.appKey);
  }

  /** Debug form with every credential redacted. */
  toJSON(): Record<string, unknown> {
    const out: Record<string, unknown> = {
      ...describeCommonConfig(this),
      protocol: this.protocol,
      otlpEndpoint: redactUrl(this.otlpTracesEndpoint),
      site: this.site,
      apiUrl: redactUrl(this.apiUrl),
      tags: { ...this.tags },
      apiKey: this.apiKey === undefined ? null : REDACTED,
      appKey: this.appKey === undefined ? null : REDACTED,
    };
    if (this.serviceName !== undefined) out.serviceName = this.serviceName;
    if (this.serviceVersion !== undefined) out.serviceVersion = this.serviceVersion;
    if (this.environment !== undefined) out.environment = this.environment;
    return out;
  }

  toString(): string {
    return `DatadogApmConfig(${JSON.stringify(this.toJSON())})`;
  }

  [INSPECT](): string {
    return this.toString();
  }
}
