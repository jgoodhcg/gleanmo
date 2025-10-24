# Performance Monitoring Requirements

## Goals
- Capture per-request and database-layer latencies without printing noisy console logs.
- Persist periodic summaries so historical metrics are queryable via XTDB entity history.
- Surface metrics through an in-app dashboard instead of raw log output.
- Provide concrete visibility into known slow paths, starting with the habit-log visualization queries.

## Architecture Sketch
- Initialize Tufte once at system startup; wrap HTTP handlers and DB helpers with profiling blocks.
- Schedule a Chime task that flushes aggregated metrics into a single `:performance-report` document; rely on XTDB immutability for time-series history.
- Provide a `/app/metrics/performance` route that reads the document’s history and renders charts using the existing viz UI helpers.
- Store the latest snapshot for each node under `:performance-report/global`; rely on XTDB history for time-series data rather than accumulating vectors in the document.

## ✅ Implementation Update (2025-10-23)
- **Request profiling middleware** is active app-wide via `obs/wrap-request-profiling`, so every Ring request records timing data into Tufte’s accumulator.
- **Background snapshots** run every 60 seconds, flushing the accumulator into `:performance-report` XTDB documents that capture instance metadata, git SHA, and per-route aggregates.
- **Super-user dashboard** now lives at `/app/monitoring/performance`, offering rolling windows, snapshot counts, and human-readable summaries for each profiled route.
- **Planned follow-ups**: Cross-instance aggregation and chart visualizations of the history are still future enhancements—current UI is textual but the storage model is in place.
