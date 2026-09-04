const AUTO_CHANGELOG_HEADING = /^#{1,6}\s*(what['’]?s changed|new contributors|full changelog)\b/i;

export function releaseToAnnouncement(release) {
  if (!release || release.draft || release.prerelease) return null;
  const publishedAt = release.published_at ?? release.created_at;
  const tag = String(release.tag_name ?? "").trim();
  if (!tag || !isIsoDate(publishedAt)) return null;

  const section = extractHumanReleaseSection(String(release.body ?? ""));
  if (!section) return null;
  const lines = section.split("\n");
  const heading = lines[0]?.match(/^#{1,6}\s+(.+)$/);
  const title = cleanInlineMarkdown(heading?.[1] ?? release.name ?? tag).trim();
  const content = cleanAnnouncementMarkdown((heading ? lines.slice(1) : lines).join("\n"));
  if (!title || !content) return null;

  return {
    id: `release-${tag}`,
    type: "release",
    title,
    content,
    publishedAt,
    versionName: tag.replace(/^v/i, "")
  };
}

export function mergeAnnouncements(releaseAnnouncements, manualAnnouncements, limit = 100) {
  const byId = new Map();
  for (const item of [...releaseAnnouncements, ...manualAnnouncements]) {
    const normalized = normalizeAnnouncement(item);
    if (normalized) byId.set(normalized.id, normalized);
  }
  return [...byId.values()]
    .sort((left, right) => Date.parse(right.publishedAt) - Date.parse(left.publishedAt))
    .slice(0, limit);
}

export function normalizeManualIndex(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new Error("announcements/manual.json must contain an object.");
  }
  if (!Array.isArray(value.announcements)) {
    throw new Error("announcements/manual.json announcements must be an array.");
  }
  const announcements = value.announcements.map((item) => {
    const normalized = normalizeAnnouncement({ ...item, type: "manual" });
    if (!normalized) throw new Error("Invalid manual announcement.");
    return normalized;
  });
  if (new Set(announcements.map((item) => item.id)).size !== announcements.length) {
    throw new Error("Manual announcement ids must be unique.");
  }
  return { schemaVersion: 1, announcements };
}

export function cleanAnnouncementMarkdown(value) {
  return String(value)
    .replace(/\r\n/g, "\n")
    .split("\n")
    .map((line) => cleanInlineMarkdown(line).replace(/^\s*[-*+]\s+/, "• ").trimEnd())
    .join("\n")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
}

function extractHumanReleaseSection(body) {
  const lines = body.replace(/\r\n/g, "\n").split("\n");
  const boundary = lines.findIndex((line) => AUTO_CHANGELOG_HEADING.test(line.trim()));
  const beforeBoundary = (boundary >= 0 ? lines.slice(0, boundary) : lines)
    .filter((line) => !/^\*\*full changelog\*\*\s*:/i.test(line.trim()))
    .join("\n")
    .trim();
  return beforeBoundary;
}

function cleanInlineMarkdown(value) {
  return String(value)
    .replace(/!\[([^\]]*)\]\([^)]*\)/g, "$1")
    .replace(/\[([^\]]+)\]\([^)]*\)/g, "$1")
    .replace(/[*_`~]/g, "")
    .replace(/^#{1,6}\s+/, "");
}

function normalizeAnnouncement(item) {
  if (!item || typeof item !== "object" || Array.isArray(item)) return null;
  const id = String(item.id ?? "").trim();
  const type = item.type === "release" ? "release" : "manual";
  const title = cleanInlineMarkdown(item.title ?? "").trim();
  const content = cleanAnnouncementMarkdown(item.content ?? "");
  const publishedAt = String(item.publishedAt ?? "").trim();
  if (!id || !title || !content || !isIsoDate(publishedAt)) return null;
  const normalized = { id, type, title, content, publishedAt };
  if (type === "release" && String(item.versionName ?? "").trim()) {
    normalized.versionName = String(item.versionName).trim();
  }
  return normalized;
}

function isIsoDate(value) {
  return typeof value === "string" && value.trim() !== "" && Number.isFinite(Date.parse(value));
}
