import http from 'k6/http';
import { check, sleep } from 'k6';
export const options = {
  vus: 20,
  duration: '5m',
  thresholds: {
    "http_reqs{status:500}": ["count>1"],
    "http_reqs{status:502}": ["count>1"],
    "http_reqs{status:503}": ["count>1"],
    "http_reqs{status:504}": ["count>1"],
  },
};
export default function () {
  const res = http.get('http://localhost:9080/orders/6441fde0ff591b2f1c9693b3');
  check(res, { 'status was 200': (r) => r.status == 200 });
  sleep(0.1);
}
