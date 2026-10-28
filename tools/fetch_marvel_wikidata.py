"""Export bounded CC0 Marvel name metadata through a public Wikidata SPARQL mirror.

No character biographies, images, credentials, or paid/private endpoints are used.
The export retains independent QIDs, source statements, language variants and
Fandom article IDs. Chinese labels are community data rather than official names.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import urllib.parse
import urllib.request

PREFIX = """PREFIX wd:<http://www.wikidata.org/entity/>
PREFIX wdt:<http://www.wikidata.org/prop/direct/>
PREFIX rdfs:<http://www.w3.org/2000/01/rdf-schema#>
PREFIX skos:<http://www.w3.org/2004/02/skos/core#>
PREFIX schema:<http://schema.org/>
"""
SCOPES = """{
  { ?item wdt:P6262 ?scopeId FILTER(STRSTARTS(LCASE(?scopeId), "marvel:")) BIND("MARVEL_FANDOM_ID" AS ?scope) }
  UNION { ?item wdt:P1080 wd:Q931597 BIND("MARVEL_UNIVERSE" AS ?scope) }
  UNION { ?item wdt:P1080 wd:Q642878 BIND("MCU" AS ?scope) }
}"""
SELECT_ITEMS = "{ SELECT DISTINCT ?item WHERE { " + SCOPES + " } }"
LANGUAGES = ["en", "zh-hans", "zh-cn", "zh", "zh-hant", "zh-tw"]
ROOTS = {
    "TEAM": "Q15720567", "ORGANIZATION": "Q14623646", "PLACE": "Q3895768",
    "EVENT": "Q136439120", "CHARACTER": "Q95074", "ITEM": "Q15706911",
}
MAX_RESPONSE = 12 * 1024 * 1024


class Client:
    def __init__(self, endpoint: str, folder: Path, request_budget: int = 30, reuse_snapshots: bool = False):
        self.endpoint = endpoint
        self.folder = folder
        self.folder.mkdir(parents=True, exist_ok=True)
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        self.ledger = []
        self.request_budget = request_budget
        self.reuse_snapshots = reuse_snapshots
        previous = folder / "requests.json"
        self.previous = {r["name"]: r for r in json.loads(previous.read_text(encoding="utf-8"))} if previous.is_file() else {}
        if previous.is_file() and not (folder / "requests-initial.json").exists():
            (folder / "requests-initial.json").write_bytes(previous.read_bytes())

    def get(self, name: str, query: str | None = None, command: str | None = None):
        if len(self.ledger) >= self.request_budget:
            raise RuntimeError("The source request budget has been reached")
        parameters = {"query": PREFIX + query, "timeout": "20s"} if query else {"cmd": command}
        url = self.endpoint + "?" + urllib.parse.urlencode(parameters)
        request = urllib.request.Request(url, headers={
            "User-Agent": "WatchersArchiveVocabulary/0.10 (CC0 name metadata research)",
            "Accept": "application/sparql-results+json",
        })
        started = datetime.now(timezone.utc).isoformat()
        record = {"name": name, "url": url, "startedUtc": started, "query": PREFIX + query if query else None}
        self.ledger.append(record)
        try:
            cached = self.folder / (name + ".json")
            if self.reuse_snapshots and cached.is_file():
                raw = cached.read_bytes()
                record.update(cached=True, originalRequest=self.previous.get(name))
            else:
                with self.opener.open(request, timeout=25) as response:
                    raw = response.read(MAX_RESPONSE + 1)
                    record["status"] = response.status
            if len(raw) > MAX_RESPONSE:
                raise RuntimeError("A metadata response exceeded its byte budget")
            parsed = json.loads(raw)
            if parsed.get("status") == "ERROR" or "exception" in parsed:
                raise RuntimeError(str(parsed)[:300])
            record.update(bytes=len(raw), sha256=hashlib.sha256(raw).hexdigest())
            (self.folder / (name + ".json")).write_bytes(raw)
            print(f"{name}: {len(raw)} metadata bytes", flush=True)
            return parsed
        except Exception as error:
            record.update(error=type(error).__name__, message=str(error)[:500])
            raise
        finally:
            (self.folder / "requests.json").write_text(json.dumps(self.ledger, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    def rows(self, name: str, query: str):
        result = self.get(name, query=query)
        return result["results"]["bindings"]


def value(row, key):
    return row.get(key, {}).get("value")


def qid(uri):
    return uri.rsplit("/", 1)[-1] if uri and uri.startswith("http://www.wikidata.org/entity/Q") else None


def has_han(text: str | None) -> bool:
    # Match build_marvel_glossary.has_han, including supplementary CJK blocks.
    ranges = ((0x3400, 0x4dbf), (0x4e00, 0x9fff), (0xf900, 0xfaff),
              (0x20000, 0x2a6df), (0x2a700, 0x2ebef), (0x2ebf0, 0x2ee5f),
              (0x2f800, 0x2fa1f), (0x30000, 0x323af))
    return bool(text and any(any(start <= ord(char) <= end for start, end in ranges) for char in text))


def select_chinese_names(labels: dict, aliases_by_language: dict) -> tuple[str | None, list[str], dict]:
    """A zh language tag alone is not evidence that a label is translated.

    Preserve every source label in variants and every alternate spelling in
    aliases; choose a display name only from labels actually containing Han.
    Never translate, borrow another entity's label or promote an alias to a
    source label. Simplified-labelled variants keep their existing priority.
    """
    variants = {language: labels[language] for language in LANGUAGES[1:] if labels.get(language)}
    chinese = next((text for text in variants.values() if has_han(text)), None)
    alternates = []
    for language in LANGUAGES[1:]:
        if labels.get(language):
            alternates.append(labels[language])
        alternates.extend(aliases_by_language.get(language, []))
    aliases = [text for text in dict.fromkeys(alternates) if text != chinese][:64]
    return chinese, aliases, variants


def response_evidence(record: dict) -> dict:
    original = record
    while isinstance(original.get("originalRequest"), dict):
        original = original["originalRequest"]
    query = record.get("query")
    return {
        "name": record["name"], "bytes": record.get("bytes"), "sha256": record.get("sha256"),
        "requestStartedUtc": original.get("startedUtc"), "status": original.get("status"),
        "reusedSnapshot": bool(record.get("cached")),
        "querySha256": hashlib.sha256(query.encode("utf-8")).hexdigest() if query else None,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--endpoint", default="https://qlever.dev/api/wikidata")
    parser.add_argument("--output", type=Path, default=Path(".tools/marvel-vocabulary/wikidata"))
    parser.add_argument("--checked-at", default=datetime.now(timezone.utc).date().isoformat())
    parser.add_argument("--maximum-items", type=int, default=12000)
    parser.add_argument("--reuse-snapshots", action="store_true")
    args = parser.parse_args()
    if not 1 <= args.maximum_items <= 20000:
        parser.error("maximum-items must be in 1..20000")
    client = Client(args.endpoint, args.output, reuse_snapshots=args.reuse_snapshots)
    stats = client.get("mirror-stats", command="stats")
    identity_rows = client.rows("scope-identities", """SELECT ?item ?label ?description WHERE {
      VALUES ?item { wd:Q931597 wd:Q642878 wd:Q173496 wd:P6262 }
      ?item rdfs:label ?label FILTER(LANG(?label)="en")
      OPTIONAL { ?item schema:description ?description FILTER(LANG(?description)="en") }
    }""")
    identities = {value(r, "item").rsplit("/", 1)[-1]: r for r in identity_rows}
    if "shared fictional universe" not in value(identities.get("Q931597", {}), "description").lower():
        raise RuntimeError("The Marvel Universe scope identity did not match the audited description")
    if value(identities.get("P6262", {}), "label") != "Fandom article ID":
        raise RuntimeError("P6262 no longer matches the expected Fandom article identifier")

    items = {}
    scope_rows = client.rows("entity-scopes", "SELECT DISTINCT ?item ?scope WHERE { " + SCOPES + " } ORDER BY ?item ?scope LIMIT " + str(args.maximum_items * 3))
    for row in scope_rows:
        identity = qid(value(row, "item"))
        if not identity:
            continue
        item = items.setdefault(identity, {"rawQid": identity, "scopes": set(), "labels": {}, "aliasesByLanguage": defaultdict(list), "claims": defaultdict(list)})
        item["scopes"].add(value(row, "scope"))
    if len(items) > args.maximum_items:
        raise RuntimeError("The scope response exceeds the explicit entity budget")

    language_values = ",".join(json.dumps(x) for x in LANGUAGES)
    for name, predicate in [("labels", "rdfs:label"), ("aliases", "skos:altLabel")]:
        rows = client.rows("entity-" + name, "SELECT DISTINCT ?item ?text WHERE { " + SELECT_ITEMS + " ?item " + predicate + " ?text FILTER(LANG(?text) IN (" + language_values + ")) } ORDER BY ?item")
        for row in rows:
            identity = qid(value(row, "item"))
            if identity not in items:
                continue
            label = row["text"]
            language = label.get("xml:lang")
            if name == "labels":
                items[identity]["labels"][language] = label["value"]
            else:
                items[identity]["aliasesByLanguage"][language].append(label["value"])

    for prop in ["P6262", "P31", "P1080"]:
        if prop == "P6262":
            query = "SELECT DISTINCT ?item ?object WHERE { ?item wdt:P6262 ?object FILTER(STRSTARTS(LCASE(?object), \"marvel:\")) } ORDER BY ?item"
        else:
            query = "SELECT DISTINCT ?item ?object WHERE { " + SELECT_ITEMS + " ?item wdt:" + prop + " ?object } ORDER BY ?item"
        for row in client.rows("entity-" + prop, query):
            identity = qid(value(row, "item"))
            if identity in items:
                items[identity]["claims"][prop].append(value(row, "object"))

    roots_query = " ".join("wd:" + root for root in ROOTS.values())
    classifications = defaultdict(set)
    type_ids = sorted({qid(t) for x in items.values() for t in x["claims"].get("P31", []) if qid(t)})
    type_values = " ".join("wd:" + t for t in type_ids)
    classified_rows = client.rows("type-category-roots", "SELECT DISTINCT ?type ?root WHERE { VALUES ?type { " + type_values + " } VALUES ?root { " + roots_query + " } ?type wdt:P279* ?root }")
    reverse_roots = {v: k for k, v in ROOTS.items()}
    type_categories = defaultdict(set)
    for row in classified_rows:
        type_categories[qid(value(row, "type"))].add(reverse_roots[qid(value(row, "root"))])
    for identity, item in items.items():
        for typ in item["claims"].get("P31", []):
            classifications[identity].update(type_categories[qid(typ)])

    records = []
    skipped = []
    for identity, item in sorted(items.items(), key=lambda pair: int(pair[0][1:])):
        labels = item["labels"]
        english = labels.get("en")
        if not english:
            skipped.append({"id": identity, "reason": "No English label"})
            continue
        chinese, chinese_aliases, variants = select_chinese_names(labels, item["aliasesByLanguage"])
        aliases = list(dict.fromkeys(item["aliasesByLanguage"].get("en", [])))[:64]
        kind = next((kind for kind in ROOTS if kind in classifications[identity]), "UNKNOWN")
        fandom = item["claims"].get("P6262", [])
        marvel_fandom = [x for x in fandom if x.lower().startswith("marvel:")]
        page_urls = ["https://marvel.fandom.com/wiki/" + urllib.parse.quote(x.split(":", 1)[1], safe="()_!,-.'") for x in marvel_fandom]
        universe_ids = [qid(x) for x in item["claims"].get("P1080", []) if qid(x)]
        scopes = sorted(item["scopes"])
        universe = "MCU" if "Q642878" in universe_ids else "Marvel Universe" if "Q931597" in universe_ids else None
        real_human = "http://www.wikidata.org/entity/Q5" in item["claims"].get("P31", [])
        records.append({
            "id": "wikidata:" + identity, "englishName": english, "chineseName": chinese,
            "aliases": aliases, "chineseAliases": chinese_aliases, "kind": kind,
            "englishSourceUrl": "https://www.wikidata.org/wiki/" + identity,
            "chineseEvidence": "COMMUNITY" if chinese else "UNTRANSLATED",
            "note": "Wikidata CC0 community metadata; explicit scope: " + ", ".join(scopes) + ". Chinese labels are not verified publisher translations.",
            "checkedAt": args.checked_at, "universe": universe,
            "rawQid": identity, "labelVariants": variants, "allLabels": labels,
            "aliasesByLanguage": dict(item["aliasesByLanguage"]), "scopes": scopes,
            "universeIds": universe_ids, "instanceOf": [qid(x) for x in item["claims"].get("P31", []) if qid(x)],
            "fandomIds": fandom, "marvelFandomIds": marvel_fandom, "fandomPageUrls": page_urls,
            "realHuman": real_human, "sourceLicense": "CC0",
        })
    output = args.output / "records.jsonl"
    with output.open("w", encoding="utf-8") as stream:
        for record in records:
            stream.write(json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n")
    responses = [response_evidence(record) for record in client.ledger]
    summary = {
        "source": "Wikidata", "license": "CC0", "licenseUrl": "https://www.wikidata.org/wiki/Wikidata:Licensing",
        "mirrorEndpoint": args.endpoint, "mirrorProject": "https://github.com/ad-freiburg/qlever-ui",
        "mirrorIndex": stats.get("name-index"),
        "fetchedAtUtc": max(record["requestStartedUtc"] for record in responses),
        "exportedAtUtc": datetime.now(timezone.utc).isoformat(),
        "checkedAt": args.checked_at, "sourceScopes": {"Q931597": "shared fictional Marvel Comics universe", "Q642878": "MCU; retained separately", "P6262": "explicit marvel: Fandom article identifier"},
        "entities": len(records), "withChineseLabel": sum(bool(x["chineseName"]) for x in records),
        "withChineseAliases": sum(any(has_han(alias) for alias in x["chineseAliases"]) for x in records),
        "nonHanChineseLanguageLabels": sum(not has_han(label) for x in records for label in x["labelVariants"].values()),
        "entitiesWithNonHanChineseLanguageLabels": sum(any(not has_han(label) for label in x["labelVariants"].values()) for x in records),
        "chineseSelectionRule": "First Han-containing source label in zh-hans,zh-cn,zh,zh-hant,zh-tw order; non-Han labels retained as metadata/aliases only",
        "withExactMarvelPageCrosswalk": sum(bool(x["fandomPageUrls"]) for x in records),
        "byKind": dict(Counter(x["kind"] for x in records)), "realHumanRecords": sum(x["realHuman"] for x in records),
        "scopeEntityCounts": {scope: sum(scope in x["scopes"] for x in records) for scope in ["MARVEL_FANDOM_ID", "MARVEL_UNIVERSE", "MCU"]},
        "skipped": skipped, "sha256": hashlib.sha256(output.read_bytes()).hexdigest(),
        "requests": len(client.ledger), "metadataBytes": sum(x.get("bytes", 0) for x in client.ledger),
        "networkRequestsThisExport": sum(not bool(record.get("cached")) for record in client.ledger),
        "responseEvidence": responses,
    }
    (args.output / "manifest.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2), flush=True)


if __name__ == "__main__":
    main()
