"""Grow Codes SDK for Datadog APM.

Tracing exports OTLP to the Datadog Agent with the OpenTelemetry SDK (:func:`init_tracing`); the
Datadog propagator, id helpers, log correlation and error attributes make the spans look native in
Datadog; :class:`DatadogSpansQuery` reads spans back through the Datadog SaaS Spans API.

>>> from grow_datadog_apm import DatadogApmClient, init_tracing
>>> client = DatadogApmClient.from_env()  # doctest: +SKIP
>>> handle = init_tracing(service_name="checkout")  # doctest: +SKIP
"""

from . import _core
from ._core import *  # noqa: F403
from ._version import __version__
from .client import AgentInfo, AsyncDatadogApmClient, DatadogApmClient
from .config import (
    AGENT_TRACE_PORT,
    DEFAULT_PROTOCOL,
    DEFAULT_URL,
    OTLP_GRPC_PORT,
    OTLP_HTTP_PORT,
    DatadogApmConfig,
    DatadogOtlpProtocol,
    otlp_traces_url,
    parse_datadog_tags,
)

__all__ = [
    "__version__",
    "DatadogApmClient",
    "AsyncDatadogApmClient",
    "AgentInfo",
    "DatadogApmConfig",
    "DatadogOtlpProtocol",
    "DEFAULT_URL",
    "DEFAULT_PROTOCOL",
    "OTLP_HTTP_PORT",
    "OTLP_GRPC_PORT",
    "AGENT_TRACE_PORT",
    "otlp_traces_url",
    "parse_datadog_tags",
]
__all__ += _core.__all__

from . import _tracing  # noqa: E402
from ._tracing import *  # noqa: E402, F403

__all__ += _tracing.__all__

from . import correlation, ids, mapping, propagator, setup, sites, span_errors, spans  # noqa: E402
from .correlation import *  # noqa: E402, F403
from .ids import *  # noqa: E402, F403
from .mapping import *  # noqa: E402, F403
from .propagator import *  # noqa: E402, F403
from .setup import *  # noqa: E402, F403
from .sites import *  # noqa: E402, F403

# These replace the generic helpers of the same name from ._tracing: they also set the Datadog
# ``error.type``, ``error.message`` and ``error.stack`` span attributes.
from .span_errors import *  # noqa: E402, F403
from .spans import *  # noqa: E402, F403

for _module in (correlation, ids, mapping, propagator, setup, sites, span_errors, spans):
    __all__ += [name for name in _module.__all__ if name not in __all__]
del _module
