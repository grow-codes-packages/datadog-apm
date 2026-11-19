"""The async client and the async Spans API; cancel a call by cancelling its task."""

from __future__ import annotations

import asyncio

import grow_datadog_apm as sdk


async def main() -> None:
    async with sdk.AsyncDatadogApmClient.from_env() as client:
        print("agent:", await client.health())
        print("version:", await client.server_version())

        # Cancellation: the SDK raises CancelledError (also an asyncio.CancelledError).
        task = asyncio.create_task(client.ping())
        task.cancel()
        try:
            await task
        except asyncio.CancelledError:
            print("ping cancelled")

        if client.config.has_query_credentials:
            spans = client.spans
            result = await spans.aggregate_spans(
                [sdk.SpanCompute("count"), sdk.SpanCompute("pc90", "@duration")],
                [sdk.SpanGroupBy("service", limit=5)],
                from_="now-1h",
            )
            for bucket in result.buckets:
                print(bucket.by, bucket.computes)
            async for item in spans.search_spans(query="status:error", limit=3):
                print("error span:", item.span.service_name, item.span.name)
                break


if __name__ == "__main__":
    asyncio.run(main())
