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
const cdkIndex = await mirrorCdkIndex();

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
    pluginPackages: pluginMarket.mirroredPackages,
    discoveredPlugins: pluginMarket.discoveredPlugins,
    cdkEntries: cdkIndex.entries
  }
});

console.log(`CDN files generated in ${outDir}`);
console.log(`Release: ${tag}`);
console.log(`APK: ${apkAsset.name}`);
console.log(`SHA-256: ${apkSha256}`);
console.log(`Plugin packages: ${pluginMarket.mirroredPackages}`);
console.log(`Discovered plugins: ${pluginMarket.discoveredPlugins}`);
console.log(`CDK entries: ${cdkIndex.entries}`);

async function mirrorPluginMarketIndex() {
  const market = await readPluginMarketIndex();
  const discoveredPlugins = await discoverPluginMarketPlugins();
  const mergedMarket = mergePluginMarkets(market, discoveredPlugins);

  let mirroredPackages = 0;
  const plugins = [];

  for (const plugin of mergedMarket.plugins) {
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
    ...mergedMarket,
    updatedAt: now,
    plugins
  });

  return {
    mirroredPackages,
    discoveredPlugins: discoveredPlugins.length
  };
}

async function mirrorCdkIndex() {
  const sourcePath = path.join(rootDir, "cdk", "index.json");
  const fallback = {
    schemaVersion: 1,
    updatedAt: now,
    entries: []
  };

  const index = existsSync(sourcePath)
    ? JSON.parse(await readFile(sourcePath, "utf8"))
    : fallback;
  validateCdkIndex(index);

  const output = {
    ...index,
    updatedAt: index.updatedAt ?? now,
    entries: index.entries ?? []
  };
  await writeJson("/cdk/index.json", output);

  return {
    entries: output.entries.length
  };
}

function validateCdkIndex(index) {
  if (!index || typeof index !== "object") {
    throw new Error("cdk/index.json must contain an object.");
  }
  if (index.entries !== undefined && !Array.isArray(index.entries)) {
    throw new Error("cdk/index.json entries must be an array.");
  }

  for (const entry of index.entries ?? []) {
    validateCdkEntry(entry);
  }
}

function validateCdkEntry(entry) {
  if (!entry || typeof entry !== "object") {
    throw new Error("Invalid CDK entry in cdk/index.json.");
  }
  if (typeof entry.id !== "string" || entry.id.length === 0) {
    throw new Error("CDK entry id is required.");
  }
  assertSafeSegment("CDK entry id", entry.id);
  if (typeof entry.code !== "string" || entry.code.length === 0) {
    throw new Error(`CDK entry ${entry.id} code is required.`);
  }
  for (const field of ["title", "description", "startsAt", "endsAt"]) {
    if (entry[field] !== undefined && typeof entry[field] !== "string") {
      throw new Error(`CDK entry ${entry.id} ${field} must be a string.`);
    }
  }
}

async function readPluginMarketIndex() {
  const sourcePath = path.join(rootDir, "plugin-market", "index.json");
  const fallback = {
    schemaVersion: 1,
    updatedAt: now,
    plugins: []
  };

  if (!existsSync(sourcePath)) {
    return fallback;
  }

  const raw = await readFile(sourcePath, "utf8");
  const market = JSON.parse(raw);
  if (!Array.isArray(market.plugins)) {
    throw new Error("plugin-market/index.json must contain a plugins array.");
  }
  return market;
}

async function discoverPluginMarketPlugins() {
  const sourcePath = path.join(rootDir, "plugin-market", "discovery.json");
  if (!existsSync(sourcePath)) {
    return [];
  }

  const raw = await readFile(sourcePath, "utf8");
  const discovery = JSON.parse(raw);
  if (!Array.isArray(discovery.sources)) {
    throw new Error("plugin-market/discovery.json must contain a sources array.");
  }

  const plugins = [];

  for (const source of discovery.sources) {
    if (source?.enabled === false) {
      continue;
    }

    validateDiscoverySource(source);

    try {
      const plugin = await discoverPluginFromGitHubRelease(source);
      plugins.push(plugin);
    } catch (error) {
      if (source.required === true) {
        throw error;
      }
      console.warn(`Plugin discovery skipped for ${source.pluginId}: ${error.message}`);
    }
  }

  return plugins;
}

async function discoverPluginFromGitHubRelease(source) {
  const release = await fetchJson(
    `https://api.github.com/repos/${source.owner}/${source.repo}/releases/latest`
  );
  const assetName = source.releaseAsset ?? "market-entry.json";
  const asset = release.assets?.find((item) => item.name === assetName);

  if (!asset?.browser_download_url) {
    throw new Error(
      `Latest release ${release.tag_name ?? "unknown"} does not contain ${assetName}.`
    );
  }

  const marketEntry = await fetchPublicJson(asset.browser_download_url);
  const plugin = normalizeDiscoveredPlugin(marketEntry, source);
  validateDiscoveredPlugin(source, plugin);
  return plugin;
}

function normalizeDiscoveredPlugin(marketEntry, source) {
  if (marketEntry && Array.isArray(marketEntry.plugins)) {
    const plugin = marketEntry.plugins.find((item) => item?.id === source.pluginId);
    if (!plugin) {
      throw new Error(`market-entry.json does not contain plugin ${source.pluginId}.`);
    }
    return plugin;
  }
  return marketEntry;
}

function validateDiscoverySource(source) {
  if (!source || typeof source !== "object") {
    throw new Error("Invalid discovery source in plugin-market/discovery.json.");
  }
  if (typeof source.pluginId !== "string" || source.pluginId.length === 0) {
    throw new Error("Discovery source pluginId is required.");
  }
  if (typeof source.owner !== "string" || source.owner.length === 0) {
    throw new Error(`Discovery source ${source.pluginId} owner is required.`);
  }
  if (typeof source.repo !== "string" || source.repo.length === 0) {
    throw new Error(`Discovery source ${source.pluginId} repo is required.`);
  }
  assertSafeSegment("discovery plugin id", source.pluginId);
  assertSafeSegment("GitHub owner", source.owner);
  assertSafeSegment("GitHub repo", source.repo);
  if (source.releaseAsset !== undefined) {
    assertSafeSegment("release asset", source.releaseAsset);
  }
  if (source.allowedPermissions !== undefined && !Array.isArray(source.allowedPermissions)) {
    throw new Error(`Discovery source ${source.pluginId} allowedPermissions must be an array.`);
  }
}

function validateDiscoveredPlugin(source, plugin) {
  validatePlugin(plugin);

  if (plugin.id !== source.pluginId) {
    throw new Error(`Discovered plugin id mismatch: expected ${source.pluginId}, got ${plugin.id}.`);
  }
  if (typeof plugin.latestVersion !== "string" || plugin.latestVersion.length === 0) {
    throw new Error(`Discovered plugin ${plugin.id} latestVersion is required.`);
  }
  if (!Array.isArray(plugin.releases) || plugin.releases.length === 0) {
    throw new Error(`Discovered plugin ${plugin.id} must contain at least one release.`);
  }

  validateAllowedPermissions(source, plugin);

  for (const release of plugin.releases) {
    validatePluginRelease(plugin, release);
    validateTrustedReleaseUrl(source, release.downloadUrl);
  }
}

function validateAllowedPermissions(source, plugin) {
  const permissions = plugin.permissions ?? [];
  if (!Array.isArray(permissions)) {
    throw new Error(`Discovered plugin ${plugin.id} permissions must be an array.`);
  }
  if (source.allowedPermissions === undefined) {
    return;
  }

  const allowed = new Set(source.allowedPermissions);
  const denied = permissions.filter((permission) => !allowed.has(permission));
  if (denied.length > 0) {
    throw new Error(
      `Discovered plugin ${plugin.id} requests unapproved permissions: ${denied.join(", ")}`
    );
  }
}

function validateTrustedReleaseUrl(source, value) {
  let url;
  try {
    url = new URL(value);
  } catch {
    throw new Error(`Invalid release downloadUrl for ${source.pluginId}: ${value}`);
  }

  const expectedPrefix = `/${source.owner}/${source.repo}/releases/download/`.toLowerCase();
  const actualPath = decodeURIComponent(url.pathname).toLowerCase();
  if (url.hostname.toLowerCase() !== "github.com" || !actualPath.startsWith(expectedPrefix)) {
    throw new Error(
      `Release downloadUrl for ${source.pluginId} must point to ${source.owner}/${source.repo} GitHub releases.`
    );
  }
}

function mergePluginMarkets(market, discoveredPlugins) {
  if (discoveredPlugins.length === 0) {
    return market;
  }

  const plugins = [...market.plugins];
  const indexById = new Map(plugins.map((plugin, index) => [plugin.id, index]));

  for (const discoveredPlugin of discoveredPlugins) {
    const existingIndex = indexById.get(discoveredPlugin.id);
    if (existingIndex === undefined) {
      indexById.set(discoveredPlugin.id, plugins.length);
      plugins.push(discoveredPlugin);
      continue;
    }

    plugins[existingIndex] = mergePluginEntry(plugins[existingIndex], discoveredPlugin);
  }

  return {
    ...market,
    plugins
  };
}

function mergePluginEntry(existingPlugin, discoveredPlugin) {
  if (
    existingPlugin.latestVersion &&
    discoveredPlugin.latestVersion &&
    compareSemanticVersions(discoveredPlugin.latestVersion, existingPlugin.latestVersion) < 0
  ) {
    throw new Error(
      `Discovered plugin ${discoveredPlugin.id} latestVersion ${discoveredPlugin.latestVersion} is older than existing ${existingPlugin.latestVersion}.`
    );
  }

  const releasesByVersion = new Map();
  for (const release of existingPlugin.releases ?? []) {
    releasesByVersion.set(release.version, release);
  }
  for (const release of discoveredPlugin.releases ?? []) {
    releasesByVersion.set(release.version, {
      ...(releasesByVersion.get(release.version) ?? {}),
      ...release
    });
  }

  return {
    ...existingPlugin,
    ...discoveredPlugin,
    releases: [...releasesByVersion.values()]
  };
}

function compareSemanticVersions(left, right) {
  const leftParts = parseSemanticVersion(left);
  const rightParts = parseSemanticVersion(right);
  if (!leftParts || !rightParts) {
    return 0;
  }

  const length = Math.max(leftParts.length, rightParts.length);
  for (let index = 0; index < length; index += 1) {
    const leftValue = leftParts[index] ?? 0;
    const rightValue = rightParts[index] ?? 0;
    if (leftValue !== rightValue) {
      return leftValue - rightValue;
    }
  }
  return 0;
}

function parseSemanticVersion(value) {
  const parts = String(value).replace(/^v/i, "").split(".");
  if (parts.some((part) => !/^\d+$/.test(part))) {
    return null;
  }
  return parts.map((part) => Number.parseInt(part, 10));
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

async function fetchPublicJson(url) {
  const response = await fetch(url, {
    headers: {
      Accept: "application/json",
      "User-Agent": "bilicraft-cdn-sync"
    }
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
