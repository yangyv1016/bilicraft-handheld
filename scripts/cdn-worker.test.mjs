import assert from "node:assert/strict";
import test from "node:test";
import worker from "./cdn-worker.mjs";

const manifest = {
  schemaVersion: 2,
  tag: "v1.2.0",
  artifact: {
    name: "bilicraft handle-1.2.0.apk",
    path: "/app/releases/v1.2.0/bilicraft handle-1.2.0.apk",
    sourceUrl: "https://github.com/example/app/releases/download/v1.2.0/bilicraft.handle-1.2.0.apk",
    sha256: "a".repeat(64),
    size: 10,
    parts: [
      { path: "/app/releases/v1.2.0/parts/part-001.bin", size: 5 },
      { path: "/app/releases/v1.2.0/parts/part-002.bin", size: 5 }
    ]
  }
};

test("streams APK parts for the stable CDN URL", async () => {
  const env = createEnvironment();
  const response = await worker.fetch(
    new Request("https://cdn.example/app/releases/v1.2.0/bilicraft%20handle-1.2.0.apk"),
    env
  );

  assert.equal(response.status, 200);
  assert.equal(response.headers.get("content-length"), "10");
  assert.equal(await response.text(), "helloworld");
});

test("keeps old app GitHub-prefix downloads compatible", async () => {
  const env = createEnvironment();
  const response = await worker.fetch(
    new Request(
      "https://cdn.example/https://github.com/example/app/releases/download/v1.2.0/bilicraft.handle-1.2.0.apk"
    ),
    env
  );

  assert.equal(response.status, 200);
  assert.equal(await response.text(), "helloworld");
});

test("maps the old app GitHub API prefix to mirrored release JSON", async () => {
  const env = createEnvironment();
  const response = await worker.fetch(
    new Request("https://cdn.example/https://api.github.com/repos/example/app/releases/latest"),
    env
  );

  assert.equal(response.status, 200);
  assert.equal((await response.json()).tag_name, "v1.2.0");
});

test("redirects the stable latest URL to the streamed APK URL", async () => {
  const env = createEnvironment();
  const response = await worker.fetch(new Request("https://cdn.example/download/latest"), env);

  assert.equal(response.status, 302);
  assert.equal(
    response.headers.get("location"),
    "https://cdn.example/app/releases/v1.2.0/bilicraft%20handle-1.2.0.apk"
  );
});

function createEnvironment() {
  const assets = new Map([
    ["/app/releases/v1.2.0/manifest.json", jsonResponse(manifest)],
    [
      "/app/releases/latest.json",
      jsonResponse({
        latest: {
          apk: {
            downloadUrl:
              "https://cdn.example/app/releases/v1.2.0/bilicraft%20handle-1.2.0.apk"
          }
        }
      })
    ],
    [
      "/app/github-api/repos/example/app/releases/latest.json",
      jsonResponse({ tag_name: "v1.2.0" })
    ],
    ["/app/releases/v1.2.0/parts/part-001.bin", new Response("hello")],
    ["/app/releases/v1.2.0/parts/part-002.bin", new Response("world")]
  ]);
  return {
    ASSETS: {
      async fetch(request) {
        const pathname = new URL(request.url).pathname;
        const response = assets.get(pathname);
        return response ? response.clone() : new Response("Not found", { status: 404 });
      }
    }
  };
}

function jsonResponse(value) {
  return new Response(JSON.stringify(value), {
    headers: { "Content-Type": "application/json" }
  });
}
