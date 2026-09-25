import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE = __ENV.CORE_BASE_URL || 'http://localhost:8080';
const AGENT_KEY = __ENV.AGENT_API_KEY || 'dev-agent-key-change-me';
const SKU = 'FLASH-HOT';

export const successes = new Counter('reserve_successes');
export const outOfStock = new Counter('reserve_out_of_stock');
export const otherErrors = new Counter('reserve_other_errors');
export const reserveLatency = new Trend('reserve_latency_ms');

export const options = {
    scenarios: {
        flash_sale: { executor: 'per-vu-iterations', vus: 500, iterations: 1, maxDuration: '60s' },
    },
    thresholds: { http_req_failed: ['rate<0.01'] },
};

export function setup() {
    return { sessionPrefix: `flash-${Date.now()}` };
}

export default function (data) {
    const sessionId = `${data.sessionPrefix}-${__VU}`;
    const headers = { 'X-Agent-Key': AGENT_KEY, 'X-Session-Id': sessionId, 'Content-Type': 'application/json' };

    const cartRes = http.post(`${BASE}/api/carts`, JSON.stringify({}), { headers });
    if (cartRes.status !== 200) { otherErrors.add(1); return; }
    const cartId = cartRes.json('cartId');

    const start = Date.now();
    const addRes = http.post(`${BASE}/api/carts/${cartId}/items`,
        JSON.stringify({ sku: SKU, qty: 1 }), { headers });
    reserveLatency.add(Date.now() - start);

    if (addRes.status === 200) {
        successes.add(1);
    } else if (addRes.status === 409) {
        outOfStock.add(1);
    } else {
        otherErrors.add(1);
        check(addRes, { 'unexpected status logged': () => false });
    }
}