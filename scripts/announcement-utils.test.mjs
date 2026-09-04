import test from "node:test";
import assert from "node:assert/strict";
import {
  mergeAnnouncements,
  normalizeManualIndex,
  releaseToAnnouncement
} from "./announcement-utils.mjs";

test("release announcement keeps human text and drops generated changelog", () => {
  const announcement = releaseToAnnouncement({
    tag_name: "v1.2.2",
    name: "v1.2.2",
    draft: false,
    prerelease: false,
    published_at: "2026-09-01T04:05:17Z",
    body: `## 掌上碧玺 v1.2.2\n\n今天吃韭黄炒蛋\n\n- 修复下载进度\n\n## What's Changed\n* internal change`
  });

  assert.equal(announcement.title, "掌上碧玺 v1.2.2");
  assert.equal(announcement.content, "今天吃韭黄炒蛋\n\n• 修复下载进度");
  assert.equal(announcement.versionName, "1.2.2");
  assert.doesNotMatch(announcement.content, /internal change/);
});

test("manual announcements override matching ids and history is newest first", () => {
  const merged = mergeAnnouncements(
    [{ id: "same", type: "release", title: "旧标题", content: "旧内容", publishedAt: "2026-08-01T00:00:00Z" }],
    [
      { id: "same", title: "修正标题", content: "修正内容", publishedAt: "2026-08-02T00:00:00Z" },
      { id: "new", title: "新公告", content: "最新内容", publishedAt: "2026-09-01T00:00:00Z" }
    ]
  );

  assert.deepEqual(merged.map((item) => item.id), ["new", "same"]);
  assert.equal(merged[1].title, "修正标题");
  assert.equal(merged[1].type, "manual");
});

test("manual index rejects incomplete announcements", () => {
  assert.throws(
    () => normalizeManualIndex({ schemaVersion: 1, announcements: [{ id: "broken" }] }),
    /Invalid manual announcement/
  );
});
