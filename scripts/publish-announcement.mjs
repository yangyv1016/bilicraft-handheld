import { randomUUID } from "node:crypto";
import { readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { mergeAnnouncements, normalizeManualIndex } from "./announcement-utils.mjs";

const rootDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const indexPath = path.resolve(rootDir, "announcements/manual.json");
const args = parseArgs(process.argv.slice(2));

if (args.help) {
  printUsage();
  process.exit(0);
}

const title = String(args.title ?? "").trim();
const content = args.contentFile
  ? (await readFile(path.resolve(process.cwd(), args.contentFile), "utf8")).trim()
  : String(args.content ?? "").trim();
if (!title || !content) {
  printUsage();
  throw new Error("--title and --content (or --content-file) are required.");
}

const current = normalizeManualIndex(JSON.parse(await readFile(indexPath, "utf8")));
const announcement = {
  id: String(args.id ?? `manual-${randomUUID()}`).trim(),
  type: "manual",
  title,
  content,
  publishedAt: String(args.publishedAt ?? new Date().toISOString()).trim()
};
const announcements = mergeAnnouncements([], [announcement, ...current.announcements]);
await writeFile(
  indexPath,
  `${JSON.stringify({ schemaVersion: 1, announcements }, null, 2)}\n`,
  "utf8"
);

console.log(`Announcement saved: ${announcement.id}`);
console.log("Commit announcements/manual.json and push master to deploy it to the CDN.");

function parseArgs(values) {
  const result = {};
  for (let index = 0; index < values.length; index++) {
    const key = values[index];
    if (key === "--help" || key === "-h") {
      result.help = true;
      continue;
    }
    const name = {
      "--title": "title",
      "--content": "content",
      "--content-file": "contentFile",
      "--id": "id",
      "--published-at": "publishedAt"
    }[key];
    if (!name || index + 1 >= values.length) throw new Error(`Unknown or incomplete argument: ${key}`);
    result[name] = values[++index];
  }
  return result;
}

function printUsage() {
  console.log("Usage:");
  console.log('  node scripts/publish-announcement.mjs --title "公告标题" --content "公告正文"');
  console.log('  node scripts/publish-announcement.mjs --title "公告标题" --content-file notice.txt');
}
