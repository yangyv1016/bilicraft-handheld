"""Regenerate the fixed Minecraft 1.21.11 inventory assets (not run during builds)."""
import hashlib
import json
from pathlib import Path
from urllib.request import urlopen

TARGET = Path(__file__).resolve().parents[1] / "app/src/main/assets/minecraft/inventory-774"
DATA = "https://raw.githubusercontent.com/PrismarineJS/minecraft-data/8ffb321c74cffe779acf5c447d08c473c4c291d7/data/pc/1.21.11/"
SOURCES = {
    "protocol": DATA + "protocol.json",
    "items": DATA + "items.json",
    "defaults": "https://raw.githubusercontent.com/misode/mcmeta/1.21.11-summary/item_components/data.json",
}


def main():
    documents, provenance = {}, {}
    for name, url in SOURCES.items():
        with urlopen(url) as response:
            data = response.read()
        documents[name] = json.loads(data)
        provenance[name] = {"url": url, "sha256": hashlib.sha256(data).hexdigest()}
    types = documents["protocol"]["types"]
    required = set()

    def visit(node):
        if isinstance(node, str):
            if node in types and node not in required:
                required.add(node)
                if types[node] != "native":
                    visit(types[node])
            return
        kind, args = node
        if kind == "container":
            for entry in args:
                visit(entry["type"])
        elif kind == "switch":
            for child in args["fields"].values():
                visit(child)
            if "default" in args:
                visit(args["default"])
        elif kind == "option":
            visit(args)
        elif kind in ("array", "mapper"):
            visit(args["type"])
        elif kind == "registryEntryHolder":
            visit(args["otherwise"]["type"])
        elif kind == "registryEntryHolderSet":
            visit(args["base"]["type"])
            visit(args["otherwise"]["type"])
        elif kind not in ("pstring", "bitfield"):
            raise ValueError(f"Unsupported schema: {kind}")

    visit("Slot")
    output = {
        "slot-schema": {name: types[name] for name in sorted(required)},
        "items": {str(item["id"]): item["name"] for item in documents["items"]},
        "item-components": documents["defaults"],
        "sources": provenance,
    }
    TARGET.mkdir(parents=True, exist_ok=True)
    for name, data in output.items():
        (TARGET / f"{name}.json").write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")


if __name__ == "__main__":
    main()
