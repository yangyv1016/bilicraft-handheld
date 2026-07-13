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
const safeSegmentPattern = /^[A-Za-z0-9._-]+$/;
const safeBhPluginFilePattern = /^[A-Za-z0-9._-]+\.bhplugin$/i;
const sha256Pattern = /^[a-fA-F0-9]{64}$/;

await rm(outDir, { recursive: true, force: true });
await mkdir(outDir, { recursive: true });

const pluginMarket = await mirrorPluginMarketIndex();

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
    apkSize,
    pluginPackages: pluginMarket.mirroredPackages
  }
});

console.log(`CDN files generated in ${outDir}`);
console.log(`Release: ${tag}`);
console.log(`APK: ${apkAsset.name}`);
console.log(`SHA-256: ${apkSha256}`);

async function mirrorPluginMarketIndex() {
  const sourcePath = path.join(rootDir, "plugin-market", "index.json");
  const fallback = {
    schemaVersion: 1,
    updatedAt: now,
    plugins: []
  };

  if (!existsSync(sourcePath)) {
    await writeJson("/plugin-market/index.json", fallback);
    return { mirroredPackages: 0 };
  }

  const raw = await readFile(sourcePath, "utf8");
  const market = JSON.parse(raw);
  if (!Array.isArray(market.plugins)) {
    throw new Error("plugin-market/index.json must contain a plugins array.");
  }

  let mirroredPackages = 0;
  const plugins = [];

  for (const plugin of market.plugins) {
    validatePlugin(plugin);
    const releases = [];

    for (const release of plugin.releases ?? []) {
      releases.push(await mirrorPluginRelease(plugin, release));
      mirroredPackages += 1;
    }

    plugins.push({
      ...plugin,
      releases
    });
  }

  await writeJson("/plugin-market/index.json", {
    ...market,
    plugins
  });

  return { mirroredPackages };
}

async function mirrorPluginRelease(plugin, release) {
  validatePluginRelease(plugin, release);

  const bytes = await fetchBytes(release.downloadUrl);
  verifyBhPluginBytes(bytes, `${plugin.id}@${release.version}`);

  const expectedSha256 = release.sha256.toLowerCase();
  const actualSha256 = createHash("sha256").update(bytes).digest("hex");
  if (actualSha256 !== expectedSha256) {
    throw new Error(
      `SHA-256 mismatch for ${plugin.id}@${release.version}: expected ${expectedSha256}, got ${actualSha256}`
    );
  }

  const fileName = choosePluginFileName(plugin, release);
  const cdnPath = `/plugins/${plugin.id}/${release.version}/${fileName}`;
  const cdnUrl = `${cdnRoot}/plugins/${encodeURIComponent(plugin.id)}/${encodeURIComponent(release.version)}/${encodeURIComponent(fileName)}`;

  await writeBinary(cdnPath, bytes);
  await writeJson(`/plugins/${plugin.id}/${release.version}/manifest.json`, {
    schemaVersion: 1,
    updatedAt: now,
    plugin: {
      id: plugin.id,
      name: plugin.name ?? plugin.id
    },
    release: {
      version: release.version,
      apiVersion: release.apiVersion ?? null,
      sourceUrl: release.downloadUrl
    },
    artifact: {
      name: fileName,
      path: cdnPath,
      downloadUrl: cdnUrl,
      sha256: actualSha256,
      size: bytes.byteLength
    }
  });

  return {
    ...release,
    downloadUrl: cdnUrl,
    sha256: actualSha256,
    size: bytes.byteLength
  };
}

function validatePlugin(plugin) {
  if (!plugin || typeof plugin !== "object") {
    throw new Error("Invalid plugin entry in plugin-market/index.json.");
  }
  if (typeof plugin.id !== "string" || plugin.id.length === 0) {
    throw new Error("Plugin id is required.");
  }
  assertSafeSegment("plugin id", plugin.id);
  if (plugin.releases !== undefined && !Array.isArray(plugin.releases)) {
    throw new Error(`Plugin ${plugin.id} releases must be an array.`);
  }
}

function validatePluginRelease(plugin, release) {
  if (!release || typeof release !== "object") {
    throw new Error(`Invalid release entry for plugin ${plugin.id}.`);
  }
  if (typeof release.version !== "string" || release.version.length === 0) {
    throw new Error(`Release version is required for plugin ${plugin.id}.`);
  }
  assertSafeSegment("plugin version", release.version);
  if (typeof release.downloadUrl !== "string" || release.downloadUrl.length === 0) {
    throw new Error(`Release ${plugin.id}@${release.version} downloadUrl is required.`);
  }
  if (typeof release.sha256 !== "string" || !sha256Pattern.test(release.sha256)) {
    throw new Error(`Release ${plugin.id}@${release.version} must provide a 64-character SHA-256.`);
  }
}

function choosePluginFileName(plugin, release) {
  const fileNameFromUrl = tryGetFileNameFromUrl(release.downloadUrl);
  if (fileNameFromUrl && safeBhPluginFilePattern.test(fileNameFromUrl)) {
    return fileNameFromUrl;
  }
  return `${plugin.id}-${release.version}.bhplugin`;
}

function tryGetFileNameFromUrl(value) {
  try {
    const url = new URL(value);
    const fileName = decodeURIComponent(url.pathname.split("/").filter(Boolean).pop() ?? "");
    return safeBhPluginFilePattern.test(fileName) ? fileName : null;
  } catch {
    return null;
  }
}

function verifyBhPluginBytes(bytes, label) {
  const isZip = bytes.byteLength >= 4 && bytes[0] === 0x50 && bytes[1] === 0x4b;
  if (!isZip) {
    throw new Error(`Downloaded plugin package is not a zip-compatible .bhplugin: ${label}`);
  }
}

function assertSafeSegment(label, value) {
  if (!safeSegmentPattern.test(value)) {
    throw new Error(`${label} contains unsafe characters: ${value}`);
  }
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
    headers: downloadHeaders()
  });
  if (!response.ok) {
    throw new Error(`Download failed ${response.status}: ${url}`);
  }
  return Buffer.from(await response.arrayBuffer());
}

function downloadHeaders() {
  return {
    Accept: "application/octet-stream",
    "User-Agent": "bilicraft-cdn-sync"
  };
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
