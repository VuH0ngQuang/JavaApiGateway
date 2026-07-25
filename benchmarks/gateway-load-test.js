import http from 'k6/http';
import { check, sleep } from 'k6';

// Usage:
//   k6 run benchmarks/gateway-load-test.js
//   k6 run -e TARGET_URL=http://localhost:1221/api/movies -e VUS=20 -e DURATION=30s benchmarks/gateway-load-test.js
//
// Results are saved as a timestamped JSON summary in benchmarks/results/
// so runs from different weeks (e.g. before/after connection pooling) can be diffed.

const TARGET_URL = __ENV.TARGET_URL || 'http://localhost:1221/api/movies';
const VUS = parseInt(__ENV.VUS || '10', 10);
const DURATION = __ENV.DURATION || '30s';

export const options = {
    vus: VUS,
    duration: DURATION,
    thresholds: {
        http_req_failed: ['rate<1'], // don't fail the run on errors, just report them
    },
};

export default function () {
    const res = http.get(TARGET_URL);
    check(res, {
        'status is 200': (r) => r.status === 200,
    });
    sleep(0.1);
}

export function handleSummary(data) {
    const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
    const filename = `benchmarks/results/${timestamp}.json`;

    // Save the full raw k6 metrics object (every metric, every percentile k6
    // tracked) rather than a curated subset, so nothing is lost for later
    // comparison (e.g. before/after adding Redis caching).
    const fullResult = {
        run_info: {
            timestamp: new Date().toISOString(),
            target: TARGET_URL,
            vus: VUS,
            duration: DURATION,
        },
        raw: data,
    };

    const quickSummary = {
        requests_total: data.metrics.http_reqs.values.count,
        requests_per_sec: data.metrics.http_reqs.values.rate,
        failed_rate: data.metrics.http_req_failed.values.rate,
        duration_avg_ms: data.metrics.http_req_duration.values.avg,
        duration_p95_ms: data.metrics.http_req_duration.values['p(95)'],
        duration_p99_ms: data.metrics.http_req_duration.values['p(99)'],
        duration_max_ms: data.metrics.http_req_duration.values.max,
    };

    return {
        [filename]: JSON.stringify(fullResult, null, 2),
        stdout: JSON.stringify(quickSummary, null, 2) + '\n',
    };
}
