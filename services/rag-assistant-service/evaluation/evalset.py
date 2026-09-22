"""Eval set and adversarial-probe loading, validation, and the deterministic split.

The eval set lives in `data/eval/eval_set.jsonl`, one JSON object per line:

    id, question, category, in_scope, expected_doc_id, key_facts, split, note

`key_facts` is a list of key facts; each key fact is a list of acceptable alternative spellings
(`[["$5,000", "5000"], ["per claim"]]` means: the answer must contain one of the first list AND
one of the second). Out-of-scope / decision-seeking rows have no expected chunk and no key facts.

The calibration / held-out `split` is assigned once, with a fixed seed, stratified by category,
BEFORE any LLM run (`python -m evaluation.evalset assign`). It is stored in the file so it can
never drift with the code.
"""

import argparse
import json
import random
import re
import sys
from dataclasses import dataclass
from pathlib import Path

PLAN_TYPES = ("disability", "dental", "vision", "life")
SECTIONS = ("coverage-limits", "exclusions", "waiting-periods", "definitions")
VALID_DOC_IDS = frozenset(f"{plan}_{section}" for plan in PLAN_TYPES for section in SECTIONS)

IN_SCOPE_CATEGORIES = ("factual", "paraphrase", "hard")
OUT_OF_SCOPE_CATEGORIES = ("out_of_scope", "decision_seeking")
CATEGORIES = IN_SCOPE_CATEGORIES + OUT_OF_SCOPE_CATEGORIES
SPLITS = ("calibration", "heldout")
PROBE_KINDS = ("wrong_number", "invented_fact", "negation", "other_plan")

SPLIT_SEED = 7
CALIBRATION_FRACTION = 0.4
MAX_QUESTION_CHARS = 1000  # the API's own limit (schemas.AskRequest)

EVAL_DIR = Path(__file__).resolve().parents[3] / "data" / "eval"
DEFAULT_EVAL_PATH = EVAL_DIR / "eval_set.jsonl"
DEFAULT_PROBES_PATH = EVAL_DIR / "adversarial_probes.jsonl"


class EvalSetError(ValueError):
    """The eval set or probe file is malformed."""


@dataclass(frozen=True)
class EvalRow:
    id: str
    question: str
    category: str
    in_scope: bool
    expected_doc_id: str | None
    key_facts: tuple[tuple[str, ...], ...]
    split: str | None
    note: str = ""


@dataclass(frozen=True)
class Probe:
    """A hand-written, plausible-but-unsupported answer to an in-scope eval question. It is scored
    by the real gate against that question's real retrieved chunks; the gate should reject it."""

    id: str
    eval_id: str
    kind: str
    answer: str
    note: str = ""


def _read_jsonl(path: Path) -> list[dict]:
    rows = []
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if not line.strip():
            continue
        try:
            rows.append(json.loads(line))
        except json.JSONDecodeError as exc:
            raise EvalSetError(f"{path.name}:{number}: invalid JSON ({exc})") from exc
    return rows


def _normalized_question(question: str) -> str:
    return re.sub(r"\W+", " ", question.lower()).strip()


def _parse_row(raw: dict) -> EvalRow:
    try:
        row = EvalRow(
            id=raw["id"],
            question=raw["question"],
            category=raw["category"],
            in_scope=raw["in_scope"],
            expected_doc_id=raw["expected_doc_id"],
            key_facts=tuple(tuple(alternatives) for alternatives in raw["key_facts"]),
            split=raw.get("split"),
            note=raw.get("note", ""),
        )
    except KeyError as exc:
        raise EvalSetError(f"row {raw.get('id', '?')}: missing field {exc}") from exc

    where = f"row {row.id}"
    if row.category not in CATEGORIES:
        raise EvalSetError(f"{where}: unknown category {row.category!r}")
    if not row.question.strip() or len(row.question) > MAX_QUESTION_CHARS:
        raise EvalSetError(f"{where}: question must be 1-{MAX_QUESTION_CHARS} characters")
    if row.split is not None and row.split not in SPLITS:
        raise EvalSetError(f"{where}: unknown split {row.split!r}")

    if row.category in IN_SCOPE_CATEGORIES:
        if not row.in_scope:
            raise EvalSetError(f"{where}: category {row.category} must have in_scope=true")
        if row.expected_doc_id not in VALID_DOC_IDS:
            raise EvalSetError(
                f"{where}: expected_doc_id {row.expected_doc_id!r} is not a real docId"
            )
        if not row.key_facts or any(not alternatives for alternatives in row.key_facts):
            raise EvalSetError(f"{where}: in-scope rows need key_facts, each with an alternative")
    else:
        if row.in_scope or row.expected_doc_id is not None or row.key_facts:
            raise EvalSetError(
                f"{where}: {row.category} rows need in_scope=false, no expected_doc_id, "
                "no key_facts"
            )
    return row


def load_eval_set(path: Path = DEFAULT_EVAL_PATH, *, require_splits: bool = True) -> list[EvalRow]:
    rows = [_parse_row(raw) for raw in _read_jsonl(path)]
    if not rows:
        raise EvalSetError(f"{path.name} is empty")

    ids = [row.id for row in rows]
    if len(set(ids)) != len(ids):
        raise EvalSetError("duplicate row ids")
    questions = [_normalized_question(row.question) for row in rows]
    if len(set(questions)) != len(questions):
        raise EvalSetError("duplicate questions (no padding with repeated rows)")

    if require_splits:
        if any(row.split is None for row in rows):
            raise EvalSetError("some rows have no split; run `python -m evaluation.evalset assign`")
        for split in SPLITS:
            if not any(row.split == split for row in rows):
                raise EvalSetError(f"split {split!r} is empty")
    return rows


def load_probes(rows: list[EvalRow], path: Path = DEFAULT_PROBES_PATH) -> list[Probe]:
    by_id = {row.id: row for row in rows}
    probes = []
    for raw in _read_jsonl(path):
        try:
            probe = Probe(
                raw["id"], raw["eval_id"], raw["kind"], raw["answer"], raw.get("note", "")
            )
        except KeyError as exc:
            raise EvalSetError(f"probe {raw.get('id', '?')}: missing field {exc}") from exc
        if probe.kind not in PROBE_KINDS:
            raise EvalSetError(f"probe {probe.id}: unknown kind {probe.kind!r}")
        if not probe.answer.strip():
            raise EvalSetError(f"probe {probe.id}: empty answer")
        row = by_id.get(probe.eval_id)
        if row is None or not row.in_scope:
            raise EvalSetError(f"probe {probe.id}: eval_id must name an in-scope eval row")
        probes.append(probe)
    if len({probe.id for probe in probes}) != len(probes):
        raise EvalSetError("duplicate probe ids")
    return probes


def assign_splits(
    rows: list[EvalRow], seed: int = SPLIT_SEED, fraction: float = CALIBRATION_FRACTION
) -> dict[str, str]:
    """Deterministic split, stratified by category so both splits get every kind of question.
    A category with one row goes to held-out; otherwise each split gets at least one."""
    rng = random.Random(seed)
    assignment: dict[str, str] = {}
    for category in CATEGORIES:
        ids = sorted(row.id for row in rows if row.category == category)
        rng.shuffle(ids)
        if len(ids) < 2:
            calibration_count = 0
        else:
            calibration_count = min(len(ids) - 1, max(1, round(len(ids) * fraction)))
        for index, row_id in enumerate(ids):
            assignment[row_id] = "calibration" if index < calibration_count else "heldout"
    return assignment


def write_splits(path: Path = DEFAULT_EVAL_PATH, seed: int = SPLIT_SEED) -> dict[str, str]:
    rows = load_eval_set(path, require_splits=False)
    assignment = assign_splits(rows, seed)
    lines = []
    for raw in _read_jsonl(path):
        raw["split"] = assignment[raw["id"]]
        lines.append(json.dumps(raw, ensure_ascii=False))
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return assignment


def _summary(rows: list[EvalRow]) -> str:
    lines = []
    for split in SPLITS:
        subset = [row for row in rows if row.split == split]
        by_category = {c: sum(1 for r in subset if r.category == c) for c in CATEGORIES}
        lines.append(f"{split}: n={len(subset)} {by_category}")
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("command", choices=["assign", "check"])
    parser.add_argument("--path", type=Path, default=DEFAULT_EVAL_PATH)
    parser.add_argument("--seed", type=int, default=SPLIT_SEED)
    args = parser.parse_args(argv)

    if args.command == "assign":
        existing = load_eval_set(args.path, require_splits=False)
        if any(row.split is not None for row in existing):
            print("splits are already assigned and frozen; refusing to reassign", file=sys.stderr)
            return 1
        write_splits(args.path, args.seed)
    rows = load_eval_set(args.path)
    print(f"{len(rows)} rows OK\n{_summary(rows)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
