/**
 * Cloudflare Pages advanced-mode worker.
 *
 * APK files are stored as <25 MiB static parts and exposed as one streamed
 * response so existing app versions can keep downloading a normal APK URL.
 */
export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const decodedPath = safeDecodePath(url.pathname);

    if (decodedPath.startsWith("/https://api.github.com/")) {
      const githubApiPath = decodedPath.slice("/https://api.github.com/".length);
      return fetchAsset(env, request, `/app/github-api/${githubApiPath}.json`);
    }

    if (decodedPath === "/download/latest") {
      const latest = await readJsonAsset(env, request, "/app/releases/latest.json");
      if (!latest?.latest?.apk?.downloadUrl) {
        return new Response("Latest release metadata is unavailable", { status: 503 });
      }
      return Response.redirect(latest.latest.apk.downloadUrl, 302);
    }

    const manifest = await resolveApkManifest(env, request, decodedPath);
    if (manifest) {
      return streamApk(request, env, manifest);
    }

    return env.ASSETS.fetch(request);
  }
};

async function resolveApkManifest(env, request, decodedPath) {
  const cdnMatch = decodedPath.match(/^\/app\/releases\/([^/]+)\/[^/]+\.apk$/i);
  const githubMatch = decodedPath.match(
    /^\/https:\/\/github\.com\/[^/]+\/[^/]+\/releases\/download\/([^/]+)\/[^/]+\.apk$/i
  );
  const tag = cdnMatch?.[1] ?? githubMatch?.[1];
  if (!tag) return null;

  const manifest = await readJsonAsset(
    env,
    request,
    `/app/releases/${encodeURIComponent(tag)}/manifest.json`
  );
  if (!manifest?.artifact || !Array.isArray(manifest.artifact.parts)) return null;

  if (cdnMatch && decodedPath !== manifest.artifact.path) return null;
  if (githubMatch && decodedPath.slice(1) !== manifest.artifact.sourceUrl) return null;
  return manifest;
}

async function streamApk(request, env, manifest) {
  if (request.method !== "GET" && request.method !== "HEAD") {
    return new Response("Method not allowed", {
      status: 405,
      headers: { Allow: "GET, HEAD" }
    });
  }

  const artifact = manifest.artifact;
  const headers = new Headers({
    "Content-Type": "application/vnd.android.package-archive",
    "Content-Length": String(artifact.size),
    "Content-Disposition": `attachment; filename*=UTF-8''${encodeURIComponent(artifact.name)}`,
    "Cache-Control": "public, max-age=31536000, immutable",
    "Access-Control-Allow-Origin": "*",
    ETag: `"${artifact.sha256}"`
  });
  if (request.method === "HEAD") return new Response(null, { status: 200, headers });

  const body = new ReadableStream({
    async start(controller) {
      try {
        for (const part of artifact.parts) {
          const response = await fetchAsset(env, request, part.path);
          if (!response.ok || !response.body) {
            throw new Error(`APK part is unavailable: ${part.path}`);
          }
          const reader = response.body.getReader();
          while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            controller.enqueue(value);
          }
        }
        controller.close();
      } catch (error) {
        controller.error(error);
      }
    }
  });
  return new Response(body, { status: 200, headers });
}

async function readJsonAsset(env, request, pathname) {
  const response = await fetchAsset(env, request, pathname);
  if (!response.ok) return null;
  try {
    return await response.json();
  } catch {
    return null;
  }
}

function fetchAsset(env, request, pathname) {
  const assetUrl = new URL(request.url);
  assetUrl.pathname = pathname;
  assetUrl.search = "";
  return env.ASSETS.fetch(new Request(assetUrl, { method: "GET" }));
}

function safeDecodePath(pathname) {
  try {
    return decodeURIComponent(pathname);
  } catch {
    return pathname;
  }
}
