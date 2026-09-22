"""Reading and writing the eval's JSONL artifacts (run files, reviews, calibration labels)."""

import hashlib
import json
import subprocess
from datetime import UTC, datetime
from pathlib import Path

from evaluation.evalset import DEFAULT_EVAL_PATH, EVAL_DIR
from evaluation.metrics import Reviews

RUNS_DIR = EVAL_DIR / "runs"
REVIEWS_PATH = EVAL_DIR / "reviews.jsonl"
LABELS_PATH = EVAL_DIR / "labels_calibration.jsonl"
SERVICE_DIR = Path(__file__).resolve().parents[1]
POLICY_DOCS_DIR = EVAL_DIR.parent / "synthetic" / "policy_docs"


def write_jsonl(path: Path, meta: dict, records: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = [json.dumps({"_meta": meta}, ensure_ascii=False)]
    lines += [json.dumps(record, ensure_ascii=False) for record in records]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def read_run_file(path: Path) -> tuple[dict, list[dict]]:
    """(meta, records) of a run file written by `write_jsonl`."""
    lines = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line]
    if not lines or "_meta" not in lines[0]:
        raise ValueError(f"{path} is not a run file (no _meta header)")
    return lines[0]["_meta"], lines[1:]


def load_reviews(path: Path = REVIEWS_PATH) -> Reviews:
    """Hand-review verdicts overriding the key-fact script: {(row id, run): correct|incorrect}."""
    if not path.exists():
        return {}
    reviews: Reviews = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            raw = json.loads(line)
            if raw["verdict"] not in ("correct", "incorrect"):
                raise ValueError(f"review {raw}: verdict must be correct or incorrect")
            reviews[(raw["id"], int(raw["run"]))] = raw["verdict"]
    return reviews


def _sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes().replace(b"\r\n", b"\n")).hexdigest()[:12]


def fingerprint() -> dict[str, str]:
    """Content hashes of everything that determines a run's result, so a run file identifies its
    exact code and data even when the working tree is not committed. Line endings are normalized
    so the hash agrees between Windows and WSL checkouts."""
    files = sorted(
        [*SERVICE_DIR.glob("assistant/*.py"), *SERVICE_DIR.glob("evaluation/*.py")]
        + [DEFAULT_EVAL_PATH, *POLICY_DOCS_DIR.glob("*.md")]
    )
    return {str(f.relative_to(EVAL_DIR.parent.parent)): _sha(f) for f in files}


def git_head() -> str:
    try:
        result = subprocess.run(
            ["git", "rev-parse", "HEAD"], capture_output=True, text=True, check=True,
            cwd=SERVICE_DIR,
        )
        return result.stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return "unknown"


def utc_now() -> str:
    return datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")
