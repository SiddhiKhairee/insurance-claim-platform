"""Groundedness-threshold calibration, on the CALIBRATION split only.

    python -m evaluation.calibrate skeleton --collect RUN.jsonl      # create the labels file
    python -m evaluation.calibrate show     --collect RUN.jsonl --offset 0 --limit 8
    python -m evaluation.calibrate flags    --collect RUN.jsonl      # label vs key-fact script
    python -m evaluation.calibrate choose   --collect RUN.jsonl --probes PROBES.jsonl

Labels (`data/eval/labels_calibration.jsonl`): one row per recorded provider answer,
`faithful: true` iff every claim in the answer is supported by the chunks retrieved for that
question (regardless of whether it answers the question the way the key facts expect).

The selection rule is `metrics.choose_threshold`, fixed before any scores were seen: among
thresholds that reject every unfaithful attempt (real unfaithful answers AND the adversarial
probes), take the one accepting the most faithful attempts; midpoint of the tied plateau.
"""

import argparse
import json
from pathlib import Path

from evaluation.evalset import DEFAULT_EVAL_PATH, load_eval_set
from evaluation.metrics import (
    THRESHOLD_GRID,
    Attempt,
    choose_threshold,
    gate_passes,
    key_facts_present,
    simulate_chain,
)
from evaluation.runfiles import LABELS_PATH, read_run_file


def answer_attempts(records: list[dict]) -> list[tuple[dict, int, Attempt]]:
    """(record, attempt index, attempt) for every provider answer that went through the gate."""
    found = []
    for record in records:
        for index, raw in enumerate(record["attempts"]):
            attempt = Attempt.from_dict(raw)
            if attempt.kind == "answer":
                found.append((record, index, attempt))
    return found


def load_labels(path: Path) -> dict[tuple[str, int], dict]:
    if not path.exists():
        return {}
    labels = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            raw = json.loads(line)
            labels[(raw["id"], raw["attempt_index"])] = raw
    return labels


def cmd_skeleton(records: list[dict], path: Path) -> None:
    existing = load_labels(path)
    lines = []
    for record, index, attempt in answer_attempts(records):
        row = existing.get((record["id"], index)) or {
            "id": record["id"], "attempt_index": index, "provider": attempt.provider,
            "faithful": None, "note": "",
        }
        lines.append(json.dumps(row, ensure_ascii=False))
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"{len(lines)} attempts to label in {path} (existing labels kept)")


def cmd_show(records: list[dict], rows: dict, offset: int, limit: int) -> None:
    shown = 0
    for record in records[offset:]:
        if not any(a["kind"] == "answer" for a in record["attempts"]):
            continue
        row = rows[record["id"]]
        print(f"\n=== {row.id} [{row.category}] expected={row.expected_doc_id}")
        print(f"Q: {row.question}")
        for chunk in record["retrieval"]["chunks"]:
            print(f"--- chunk {chunk['doc_id']} ({chunk['score']:.3f})\n{chunk['text']}")
        for index, raw in enumerate(record["attempts"]):
            if raw["kind"] == "answer":
                verdict = key_facts_present(raw["answer"], row.key_facts) if row.key_facts else "-"
                print(f"--- attempt {index} {raw['provider']} nli={raw['nli_score']} "
                      f"numbers_ok={raw['numbers_supported']} keyfacts={verdict}\n{raw['answer']}")
        shown += 1
        if shown >= limit:
            break


def cmd_flags(records: list[dict], rows: dict, labels: dict) -> int:
    """Disagreements between the hand label and the key-fact script, for owner review."""
    flagged = 0
    for record, index, attempt in answer_attempts(records):
        row = rows[record["id"]]
        label = labels.get((record["id"], index), {}).get("faithful")
        if label is None or not row.key_facts:
            continue
        facts = key_facts_present(attempt.answer, row.key_facts)
        if label != facts:
            flagged += 1
            print(f"{row.id} attempt {index} ({attempt.provider}): labeled "
                  f"{'faithful' if label else 'UNFAITHFUL'} but key facts "
                  f"{'present' if facts else 'MISSING'}\n  Q: {row.question}\n"
                  f"  A: {attempt.answer}\n  note: {labels[(record['id'], index)]['note']}")
    print(f"\n{flagged} flagged disagreement(s)")
    return flagged


def cmd_choose(records: list[dict], rows: dict, labels: dict, probes: list[dict]) -> None:
    faithful: list[Attempt] = []
    unfaithful: list[Attempt] = []
    missing = []
    for record, index, attempt in answer_attempts(records):
        label = labels.get((record["id"], index), {}).get("faithful")
        if label is None:
            missing.append((record["id"], index))
        (faithful if label else unfaithful).append(attempt)
    if missing:
        raise SystemExit(f"{len(missing)} attempts are unlabeled, e.g. {missing[:3]}")
    real_unfaithful = len(unfaithful)
    for probe in probes:
        unfaithful.append(Attempt("probe", "answer", probe["answer"], probe["nli_score"],
                                  probe["numbers_supported"]))

    choice = choose_threshold(faithful, unfaithful)
    in_scope = [r for r in records if rows[r["id"]].in_scope]

    print(f"{'t':>5} {'faithful acc':>13} {'unfaithful acc':>15} {'in-scope answered':>18} "
          f"{'answered+keyfacts':>18}")
    for t in THRESHOLD_GRID:
        f_acc = sum(gate_passes(a, t) for a in faithful)
        u_acc = sum(gate_passes(a, t) for a in unfaithful)
        outcomes = [(r, simulate_chain([Attempt.from_dict(a) for a in r["attempts"]], t))
                    for r in in_scope if r["attempts"]]
        answered = [(r, o) for r, o in outcomes if o.outcome == "answered"]
        correct = sum(key_facts_present(o.answer, rows[r["id"]].key_facts) for r, o in answered)
        print(f"{t:>5.2f} {f_acc:>7}/{len(faithful):<5} {u_acc:>9}/{len(unfaithful):<5} "
              f"{len(answered):>12}/{len(outcomes):<5} {correct:>12}/{len(outcomes):<5}")

    print(f"\nfaithful attempts: {len(faithful)}")
    print(f"unfaithful real attempts: {real_unfaithful}")
    print(f"adversarial probes (calibration): {len(probes)}")
    if choice.threshold is None:
        print("NO threshold rejects every unfaithful attempt -> keep the provisional value and "
              "report this as a finding")
    else:
        print(f"plateau: {choice.plateau[0]} .. {choice.plateau[-1]} "
              f"(accepts {choice.faithful_accepted}/{choice.n_faithful} faithful)")
        print(f"CHOSEN THRESHOLD: {choice.threshold}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("command", choices=["skeleton", "show", "flags", "choose"])
    parser.add_argument("--collect", type=Path, required=True)
    parser.add_argument("--probes", type=Path)
    parser.add_argument("--labels", type=Path, default=LABELS_PATH)
    parser.add_argument("--offset", type=int, default=0)
    parser.add_argument("--limit", type=int, default=8)
    args = parser.parse_args(argv)

    meta, records = read_run_file(args.collect)
    if meta["mode"] != "collect" or meta["split"] != "calibration":
        raise SystemExit("calibration must use a collect run over the calibration split only")
    rows = {row.id: row for row in load_eval_set(DEFAULT_EVAL_PATH)}
    labels = load_labels(args.labels)

    if args.command == "skeleton":
        cmd_skeleton(records, args.labels)
    elif args.command == "show":
        cmd_show(records, rows, args.offset, args.limit)
    elif args.command == "flags":
        cmd_flags(records, rows, labels)
    else:
        if args.probes is None:
            raise SystemExit("choose needs --probes (a probes run over the calibration split)")
        probe_meta, probe_records = read_run_file(args.probes)
        if probe_meta["split"] != "calibration":
            raise SystemExit("probes must come from the calibration split")
        cmd_choose(records, rows, labels, probe_records)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
