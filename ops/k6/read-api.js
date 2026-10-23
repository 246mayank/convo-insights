// Load test of the read API at a fixed arrival rate (requests start on schedule whether or not
// earlier ones have finished, so a slow server shows up as latency, not as a lower request rate).
//   docker run --rm -v "$PWD/ops/k6:/k6" -e BASE=http://host.docker.internal:8080 grafana/k6:0.54.0 run /k6/read-api.js
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE || 'http://host.docker.internal:8080';
const RATE = Number(__ENV.RATE || 200);

export const options = {
  scenarios: {
    reads: {
      executor: 'constant-arrival-rate',
      rate: RATE, timeUnit: '1s', duration: '60s',
      preAllocatedVUs: 50, maxVUs: 200,
    },
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
  thresholds: { http_req_failed: ['rate<0.01'] },
};

const brands = ['AmazonHelp', 'AppleSupport', 'Uber_Support', 'SpotifyCares', 'Delta'];
const intents = ['TECHNICAL_SUPPORT', 'DELIVERY', 'REFUND', 'PAYMENT', 'ORDER'];

export default function () {
  const r = Math.random();
  let res;
  if (r < 0.5) {
    res = http.get(`${BASE}/api/insights?size=20&intent=${intents[Math.floor(Math.random() * intents.length)]}`, { tags: { name: 'search' } });
  } else if (r < 0.8) {
    res = http.get(`${BASE}/api/brands/${brands[Math.floor(Math.random() * brands.length)]}/sentiment`, { tags: { name: 'brand-sentiment' } });
  } else {
    res = http.get(`${BASE}/api/insights/stats`, { tags: { name: 'stats' } });
  }
  check(res, { 'status 200': (x) => x.status === 200 });
}
