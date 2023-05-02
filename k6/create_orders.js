import http from 'k6/http';
import { check, sleep } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

export const options = {
  vus: 10,
  duration: '5m',
  thresholds: {
    "http_reqs{status:500}": ["count>1"],
    "http_reqs{status:502}": ["count>1"],
    "http_reqs{status:503}": ["count>1"],
    "http_reqs{status:504}": ["count>1"],
  },
};
export default function () {
  const url = 'http://localhost:9080/orders';
  const payload = JSON.stringify({
    customerCode: 'C001',
    items: [
        {
            sku: 'S001',
            barcode: 'B001',
            qty: 1,
            amount: 1000.0
        }
    ],
    amount: 1000.0
  });
  const randomUUID = uuidv4();
  const params = {
    headers: {
      'Content-Type': 'application/json',
      'x-request-id': randomUUID
    },
  };

  const res = http.post(url, payload, params);
  check(res, { 'status was 200': (r) => r.status == 200 });
  sleep(1);
}
