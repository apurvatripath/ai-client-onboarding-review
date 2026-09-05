"""Strict, dependency-free accuracy harness for the local intake extraction API."""
import argparse
from collections import Counter, defaultdict
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation
import hashlib
import json
import mimetypes
from pathlib import Path
import re
import sys
import unicodedata
from urllib import request, error, parse
import uuid

FIELDS = ("companyName", "contactName", "contactEmail", "serviceRequested", "budget",
          "targetLaunchDate", "projectSummary", "dependencies")
CELLS = ("description", "quantity", "unitPrice")
STATES = {"COMPLETE", "MISSING_INFORMATION", "MANUAL_REVIEW"}


def normalize(value, field):
    if value is None:
        return None
    value = " ".join(unicodedata.normalize("NFKC", str(value)).split()).casefold()
    if field == "targetLaunchDate":
        for fmt in ("%Y-%m-%d", "%d %B %Y", "%d %b %Y", "%B %d, %Y"):
            try:
                return datetime.strptime(value, fmt).date().isoformat()
            except ValueError:
                pass
    if field == "quantity":
        try:
            return str(Decimal(value).normalize())
        except InvalidOperation:
            pass
    return value


def percent(numerator, denominator):
    return round(100 * numerator / denominator, 2) if denominator else None


def load_dataset(folder, key):
    root = folder.resolve()
    content = key.read_bytes()
    data = json.loads(content)
    if not isinstance(data, dict) or data.get("documentType") != "INTAKE_FORM" or not isinstance(data.get("documents"), list):
        raise ValueError("Answer key needs documentType INTAKE_FORM and a documents array")
    if not data["documents"]:
        raise ValueError("No labelled documents: accuracy cannot be measured on an empty dataset")
    seen, hashes = set(), set()
    for item in data["documents"]:
        if not isinstance(item, dict) or not isinstance(item.get("file"), str) or not isinstance(item.get("fields"), dict):
            raise ValueError("Every answer needs a file string and a fields object")
        filename = item.get("file", "")
        path = (root / filename).resolve()
        if not filename or path == root or not path.is_relative_to(root):
            raise ValueError("Every document path must stay inside the sample folder")
        if path in seen:
            raise ValueError(f"Duplicate answer-key file: {filename}")
        seen.add(path)
        if not path.is_file() or path.suffix.lower() not in (".pdf", ".png", ".jpg", ".jpeg"):
            raise ValueError(f"Missing or unsupported sample: {filename}")
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        if digest in hashes:
            raise ValueError(f"Repeated document bytes would inflate dataset size: {filename}")
        hashes.add(digest)
        if set(item.get("fields", {})) != set(FIELDS):
            raise ValueError(f"{filename}: explicitly label all eight fields; use null for absent values")
        if any(v is not None and not isinstance(v, str) for v in item["fields"].values()):
            raise ValueError(f"{filename}: field answers must be strings or null")
        if not isinstance(item.get("lineItems"), list) or item.get("reviewStatus") not in STATES:
            raise ValueError(f"{filename}: lineItems array and valid reviewStatus are required")
        for row in item["lineItems"]:
            if not isinstance(row, dict) or set(row) != set(CELLS):
                raise ValueError(f"{filename}: every line item needs description, quantity, unitPrice")
            if any(v is not None and not isinstance(v, str) for v in row.values()):
                raise ValueError(f"{filename}: table answers must be strings or null")
    unlabelled = {p.resolve() for p in root.rglob("*") if p.is_file() and p.suffix.lower() in (".pdf", ".png", ".jpg", ".jpeg")} - seen
    if unlabelled:
        raise ValueError(f"{len(unlabelled)} sample document(s) have no ground truth; label them before running")
    return data, hashlib.sha256(content).hexdigest()


def upload(endpoint, path, timeout):
    content = path.read_bytes()
    digest = hashlib.sha256(content).hexdigest()
    boundary = "accuracy-" + uuid.uuid4().hex
    # Fixed harmless filename; originals never become multipart header syntax.
    mime = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"submissionId\"\r\n\r\ndoc-{digest}\r\n"
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"document\"; filename=\"sample{path.suffix.lower()}\"\r\n"
            f"Content-Type: {mime}\r\n\r\n").encode() + content + f"\r\n--{boundary}--\r\n".encode()
    req = request.Request(endpoint.rstrip("/") + "/api/extractions?reprocess=true", data=body,
                          headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
    with request.urlopen(req, timeout=timeout) as response:
        result = json.load(response)
    if result.get("inputHash") != digest:
        raise ValueError("API returned a result for a different input hash")
    return result


def evaluate(expected, actual):
    """All expected fields count. Extra table rows count against accuracy, including duplicates."""
    comparisons = []
    failed = actual is None
    actual = actual or {}
    def compare(path, metric, field, target, found, exists=True):
        found = found if isinstance(found, dict) else {}
        correct = not failed and exists and normalize(target, field) == normalize(found.get("value"), field)
        comparisons.append({"path": path, "metric": metric, "expected": target,
                            "actual": found.get("value"), "correct": correct,
                            "confidence": found.get("confidence"), "confidenceSource": found.get("confidenceSource"), "status": found.get("status"),
                            "accepted": found.get("status") == "ACCEPTED", "expectedPresent": target is not None})
    for field in FIELDS:
        compare(field, field, field, expected["fields"][field], actual.get("fields", {}).get(field),
                field in actual.get("fields", {}))
    wanted, got = expected["lineItems"], actual.get("lineItems", [])
    for index in range(max(len(wanted), len(got))):
        for cell in CELLS:
            target = wanted[index].get(cell) if index < len(wanted) else None
            found = got[index].get(cell) if index < len(got) else None
            compare(f"lineItems[{index}].{cell}", f"lineItems[].{cell}", cell, target, found,
                    index < len(wanted) and index < len(got) and cell in got[index])
    exact = not failed and all(c["correct"] for c in comparisons) and len(wanted) == len(got)
    routing = not failed and actual.get("reviewStatus") == expected["reviewStatus"]
    return {"file": expected["file"], "fieldAccuracyPercent": percent(sum(c["correct"] for c in comparisons), len(comparisons)),
            "documentExtractionExactMatch": exact, "routingCorrect": routing,
            "endToEndExactMatch": exact and routing, "expectedRoute": expected["reviewStatus"],
            "actualRoute": actual.get("reviewStatus"), "comparisons": comparisons}


def aggregate(results):
    counters = defaultdict(lambda: [0, 0])
    comparisons = [c for doc in results for c in doc["comparisons"]]
    for c in comparisons:
        counters[c["metric"]][0] += c["correct"]
        counters[c["metric"]][1] += 1
    accepted = [c for c in comparisons if c["accepted"]]
    present = [c for c in comparisons if c["expectedPresent"]]
    return {"documentCount": len(results), "evaluatedFieldCount": len(comparisons),
            "fieldAccuracyPercent": percent(sum(c["correct"] for c in comparisons), len(comparisons)),
            "documentExactMatchPercent": percent(sum(d["documentExtractionExactMatch"] for d in results), len(results)),
            "routingAccuracyPercent": percent(sum(d["routingCorrect"] for d in results), len(results)),
            "endToEndExactMatchPercent": percent(sum(d["endToEndExactMatch"] for d in results), len(results)),
            "acceptedFieldPrecisionPercent": percent(sum(c["correct"] for c in accepted), len(accepted)),
            "acceptedPresentFieldCoveragePercent": percent(sum(c["accepted"] for c in present), len(present)),
            "perField": {k: {"correct": v[0], "total": v[1], "accuracyPercent": percent(*v)} for k, v in sorted(counters.items())}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--samples", type=Path, required=True)
    parser.add_argument("--key", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=Path("output/accuracy/report.json"))
    parser.add_argument("--endpoint", default="http://127.0.0.1:8091")
    parser.add_argument("--timeout", type=int, default=1800)
    parser.add_argument("--expect-provider", help="Fail if provider differs (for example gemini/gemini-3.6-flash)")
    args = parser.parse_args()
    if parse.urlparse(args.endpoint).hostname not in ("127.0.0.1", "localhost", "::1"):
        parser.error("Use the local extraction server; remote uploads are not supported by this harness")
    try:
        dataset, key_hash = load_dataset(args.samples, args.key)
    except (ValueError, OSError, TypeError) as exc:
        parser.error(str(exc))
    results, errors = [], []
    for expected in dataset["documents"]:
        actual = None
        try:
            actual = upload(args.endpoint, args.samples / expected["file"], args.timeout)
            if args.expect_provider and actual.get("provider") != args.expect_provider:
                raise ValueError("Provider mismatch; use a fresh data store for this benchmark configuration")
        except Exception as exc:
            # Do not let unavailable/failed documents vanish from the denominator.
            errors.append({"file": expected["file"], "error": str(exc)})
            actual = None
        evaluated = evaluate(expected, actual)
        evaluated["inputHash"] = hashlib.sha256((args.samples / expected["file"]).read_bytes()).hexdigest()
        evaluated["provider"] = actual.get("provider") if actual else None
        evaluated["pipelineVersion"] = actual.get("pipelineVersion") if actual else None
        evaluated["submissionId"] = actual.get("submissionId") if actual else None
        results.append(evaluated)
        print(f"{expected['file']}: {evaluated['fieldAccuracyPercent']}% fields; route={evaluated['actualRoute']}")
    report = {"generatedAt": datetime.now(timezone.utc).isoformat(), "datasetLabel": dataset.get("label", "unlabelled"),
              "answerKeySha256": key_hash, "normalization": "NFKC, whitespace, case; ISO dates; decimal quantities. Table order is significant.",
              "summary": aggregate(results), "errors": errors, "documents": results}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    args.output.with_suffix(".md").write_text("# Extraction accuracy\n\nDataset: " + report["datasetLabel"] +
        "\n\n```json\n" + json.dumps(report["summary"], indent=2) + "\n```\n\n" +
        f"Failed requests: {len(errors)} (included as wrong answers).\n", encoding="utf-8")
    print(json.dumps(report["summary"], indent=2))
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
