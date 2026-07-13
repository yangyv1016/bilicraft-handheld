import { createHash } from "node:crypto";
import { existsSync } from "node:fs";
import { mkdir, readFile, rm, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const rootDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const outDir = path.resolve(rootDir, process.env.OUT_DIR ?? "cdn-dist");
const appOwner = process.env.APP_OWNER ?? "yangyv1016";
const appRepo = process.env.APP_REPO ?? "bilicraft-handheld";
const cdnRoot = stripTrailingSlash(process.env.CDN_ROOT ?? "https://bccdn.yanguiofficial.cn");
const now = new Date().toISOString();

await rm(outDir, { recursive: true, force: true });
await mkdir(outDir, { recursive: true });

await writePluginMarketIndex();

const latestRelease = await fetchJson(
  `https://api.github.com/repos/${appOwner}/${appRepo}/releases/latest`
);
const apkAsset = latestRelease.assets?.find((asset) =>
  asset.name?.toLowerCase().endsWith(".apk")
);

if (!apkAsset) {
  throw new Error(`Latest release ${latestRelease.tag_name} does not contain an APK asset.`);
}

const tag = latestRelease.tag_name;
const versionName = tag.replace(/^v/, "");
const apkBytes = await fetchBytes(apkAsset.browser_download_url);
const apkSize = apkBytes.byteLength;
const apkSha256 = createHash("sha256").update(apkBytes).digest("hex");
const apkCdnPath = `/app/releases/${tag}/${apkAsset.name}`;
const apkCdnUrl = `${cdnRoot}/app/releases/${encodeURIComponent(tag)}/${encodeURIComponent(apkAsset.name)}`;

await writeBinary(apkCdnPath, apkBytes);
await writeJson(`/app/releases/${tag}/manifest.json`, {
  schemaVersion: 1,
  tag,
  versionName,
  updatedAt: now,
  source: {
    releaseUrl: latestRelease.html_url,
    assetUrl: apkAsset.browser_download_url
  },
  artifact: {
    name: apkAsset.name,
    path: apkCdnPath,
    downloadUrl: apkCdnUrl,
    sha256: apkSha256,
    size: apkSize
  }
});

await writeJson("/app/releases/latest.json", {
  schemaVersion: 1,
  updatedAt: now,
  latest: {
    tag,
    versionName,
    apk: {
      name: apkAsset.name,
      downloadUrl: apkCdnUrl,
      sha256: apkSha256,
      size: apkSize
    }
  }
});

await writeJson(`/app/github-api/repos/${appOwner}/${appRepo}/releases/latest.json`, {
  url: latestRelease.url,
  html_url: latestRelease.html_url,
  id: latestRelease.id,
  tag_name: tag,
  name: latestRelease.name ?? tag,
  draft: false,
  prerelease: false,
  created_at: latestRelease.created_at,
  published_at: latestRelease.published_at,
  body: latestRelease.body ?? "",
  assets: [
    {
      id: apkAsset.id,
      name: apkAsset.name,
      content_type: apkAsset.content_type ?? "application/vnd.android.package-archive",
      size: apkSize,
      browser_download_url: apkAsset.browser_download_url
    }
  ]
});

await writeJson("/meta/sync-state.json", {
  schemaVersion: 1,
  updatedAt: now,
  mode: "github-actions",
  source: {
    appRepo: `${appOwner}/${appRepo}`,
    tag,
    releaseUrl: latestRelease.html_url
  },
  output: {
    cdnRoot,
    apkPath: apkCdnPath,
    apkSha256,
    apkSize
  }
});

console.log(`CDN files generated in ${outDir}`);
console.log(`Release: ${tag}`);
console.log(`APK: ${apkAsset.name}`);
console.log(`SHA-256: ${apkSha256}`);

async function writePluginMarketIndex() {
  const sourcePath = path.join(rootDir, "plugin-market", "index.json");
  const fallback = {
    schemaVersion: 1,
    updatedAt: now,
    plugins: []
  };

  if (!existsSync(sourcePath)) {
    await writeJson("/plugin-market/index.json", fallback);
    return;
  }

  const raw = await readFile(sourcePath, "utf8");
  JSON.parse(raw);
  await writeText("/plugin-market/index.json", `${raw.trim()}\n`);
}

async function fetchJson(url) {
  const response = await fetch(url, {
    headers: githubHeaders("application/vnd.github+json")
  });
  if (!response.ok) {
    throw new Error(`Fetch failed ${response.status}: ${url}`);
  }
  return response.json();
}

async function fetchBytes(url) {
  const response = await fetch(url, {
    headers: githubHeaders("application/octet-stream")
  });
  if (!response.ok) {
    throw new Error(`Download failed ${response.status}: ${url}`);
  }
  return Buffer.from(await response.arrayBuffer());
}

function githubHeaders(accept) {
  const headers = {
    Accept: accept,
    "User-Agent": "bilicraft-cdn-sync"
  };
  if (process.env.GITHUB_TOKEN) {
    headers.Authorization = `Bearer ${process.env.GITHUB_TOKEN}`;
  }
  return headers;
}

async function writeJson(relativePath, value) {
  await writeText(relativePath, `${JSON.stringify(value, null, 2)}\n`);
}

async function writeText(relativePath, text) {
  const targetPath = toOutPath(relativePath);
  await mkdir(path.dirname(targetPath), { recursive: true });
  await writeFile(targetPath, text, "utf8");
}

async function writeBinary(relativePath, bytes) {
  const targetPath = toOutPath(relativePath);
  await mkdir(path.dirname(targetPath), { recursive: true });
  await writeFile(targetPath, bytes);
}

function toOutPath(relativePath) {
  const normalized = relativePath.replace(/^[/\\]+/, "");
  return path.join(outDir, normalized);
}

function stripTrailingSlash(value) {
  return value.replace(/\/+$/, "");
}
