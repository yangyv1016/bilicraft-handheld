import assert from "node:assert/strict";
import test from "node:test";
import { fetchWithRetry } from "./fetch-with-retry.mjs";

test("retries server errors and preserves request headers and the successful body", async (t) => {
  const responses = [
    new Response("temporary failure", { status: 500 }),
    new Response(null, { status: 502 }),
    new Response('{"tag_name":"v1.0.0"}')
  ];
  const options = { headers: { Accept: "application/json" } };
  const fetchMock = t.mock.method(globalThis, "fetch", async (url, init) => {
    assert.equal(url, "https://example.com/releases");
    assert.equal(init, options);
    return responses[fetchMock.mock.callCount()];
  });
  t.mock.method(console, "warn", () => {});

  const response = await fetchWithRetry("https://example.com/releases", options);

  assert.equal(fetchMock.mock.callCount(), 3);
  assert.equal(responses[0].bodyUsed, true);
  assert.deepEqual(await response.json(), { tag_name: "v1.0.0" });
});

test("returns the last server error after three attempts", async (t) => {
  const fetchMock = t.mock.method(globalThis, "fetch", async () =>
    new Response("still unavailable", { status: 503 })
  );
  t.mock.method(console, "warn", () => {});

  const response = await fetchWithRetry("https://example.com/releases");

  assert.equal(fetchMock.mock.callCount(), 3);
  assert.equal(response.status, 503);
  assert.equal(await response.text(), "still unavailable");
});

test("retries a fetch network failure and keeps binary downloads intact", async (t) => {
  const bytes = new Uint8Array([0, 128, 255]);
  const fetchMock = t.mock.method(globalThis, "fetch", async () => {
    if (fetchMock.mock.callCount() === 0) throw new TypeError("fetch failed");
    return new Response(bytes);
  });
  t.mock.method(console, "warn", () => {});

  const response = await fetchWithRetry("https://example.com/app.apk");

  assert.equal(fetchMock.mock.callCount(), 2);
  assert.deepEqual(new Uint8Array(await response.arrayBuffer()), bytes);
});

test("propagates a persistent network failure after three attempts", async (t) => {
  const error = new TypeError("fetch failed");
  const fetchMock = t.mock.method(globalThis, "fetch", async () => { throw error; });
  t.mock.method(console, "warn", () => {});

  await assert.rejects(fetchWithRetry("https://example.com/releases"), (actual) => actual === error);
  assert.equal(fetchMock.mock.callCount(), 3);
});

test("does not retry client errors or successful responses", async (t) => {
  for (const status of [200, 400, 401, 403, 404, 429]) {
    const expected = new Response("response", { status });
    const fetchMock = t.mock.method(globalThis, "fetch", async () => expected);

    assert.equal(await fetchWithRetry("https://example.com/releases"), expected);
    assert.equal(fetchMock.mock.callCount(), 1);
    fetchMock.mock.restore();
  }
});

test("does not retry aborted requests or unexpected errors", async (t) => {
  for (const error of [new DOMException("aborted", "AbortError"), new Error("unexpected")]) {
    const fetchMock = t.mock.method(globalThis, "fetch", async () => { throw error; });

    await assert.rejects(fetchWithRetry("https://example.com/releases"), (actual) => actual === error);
    assert.equal(fetchMock.mock.callCount(), 1);
    fetchMock.mock.restore();
  }
});
