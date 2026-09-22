"""Score a final held-out run and print the PLAN.md section 11 numbers as markdown.

    python -m evaluation.report --final RUN.jsonl --retrieval RET.jsonl [--probes PROBES.jsonl]

How the repeats are summarized (fixed in PLAN.md section 12 before the first LLM run):
- every run gets its own rate with a Wilson 95% interval, where n = held-out QUESTIONS;
- the headline is the MEAN of the per-run rates, with the min-max range across runs, and NO
  pooled interval (repeats measure LLM variance, not sample size);
- retrieval metrics involve no LLM, so they are computed once (from the retrieval file), not
  once per repeat; the final run's retrieved chunks are cross-checked against that file.
"""

import argparse
from collections import Counter
from pathlib import Path

from evaluation.evalset import DEFAULT_EVAL_PATH, load_eval_set
from evaluation.metrics import (
    Rate,
    hit_at_k,
    is_correct,
    score_run,
    summarize_repeats,
    top1_hit,
)
from evaluation.runfiles import REVIEWS_PATH, load_reviews, read_run_file

RATE_METRICS = [
    ("answer_correctness", "Answer correctness (in-scope; abstaining counts as incorrect)"),
    ("abstention_correctness", "Abstention correctness (out-of-scope + decision-seeking)"),
    ("decision_seeking_refused_by_guardrail",
     "  of which decision-seeking refused by the guardrail (secondary)"),
    ("in_scope_abstained", "In-scope questions abstained on (over-abstention, secondary)"),
]


def pct(value: float) -> str:
    return f"{100 * value:.1f}%"


def fmt_rate(rate: Rate) -> str:
    if rate.n == 0:
        return "n/a (n=0)"
    low, high = rate.interval
    return f"{pct(rate.value)} ({rate.successes}/{rate.n}) [{pct(low)}-{pct(high)}]"


def fmt_summary(values: list[float]) -> str:
    s = summarize_repeats(values)
    return f"mean {pct(s['mean'])}, range {pct(s['min'])}-{pct(s['max'])}"


def retrieval_section(retrieval: list[dict], rows: dict, k: int) -> tuple[list[str], dict]:
    in_scope = [r for r in retrieval if rows[r["id"]].in_scope]
    lines = [f"Held-out in-scope questions: n={len(in_scope)}; k={k}. Computed once (no LLM).", ""]
    by_id = {}
    for label, key in (("As the graph runs (plan filter inferred)", "filtered"),
                       ("Secondary: unfiltered vector search", "unfiltered")):
        ids = {r["id"]: [c["doc_id"] for c in r[key]["chunks"]] for r in in_scope}
        top1 = Rate(sum(top1_hit(ids[i], rows[i].expected_doc_id) for i in ids), len(ids))
        hitk = Rate(sum(hit_at_k(ids[i], rows[i].expected_doc_id, k) for i in ids), len(ids))
        lines += [f"- {label}: top-1 {fmt_rate(top1)}; hit@{k} {fmt_rate(hitk)}"]
        by_id[key] = {r["id"]: [c["doc_id"] for c in r[key]["chunks"]] for r in retrieval}
    return lines, by_id


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--final", type=Path, required=True)
    parser.add_argument("--retrieval", type=Path, required=True)
    parser.add_argument("--probes", type=Path)
    parser.add_argument("--reviews", type=Path, default=REVIEWS_PATH)
    args = parser.parse_args(argv)

    meta, records = read_run_file(args.final)
    if meta["mode"] != "final" or meta["split"] != "heldout":
        raise SystemExit("report needs a final run over the held-out split")
    retrieval_meta, retrieval = read_run_file(args.retrieval)
    all_rows = load_eval_set(DEFAULT_EVAL_PATH)
    rows = {r.id: r for r in all_rows if r.split == "heldout"}
    reviews = load_reviews(args.reviews)

    runs: dict[int, list[dict]] = {}
    for record in records:
        runs.setdefault(record["run"], []).append(record)
    for number, run_records in runs.items():
        if {r["id"] for r in run_records} != set(rows):
            raise SystemExit(f"run {number} does not cover exactly the held-out questions")
    numbers = sorted(runs)
    scored = [score_run(runs[n], rows, reviews) for n in numbers]

    in_scope = sum(r.in_scope for r in rows.values())
    print("## Final held-out run\n")
    print(f"- date {meta['date']}, commit {meta['commit']}, temperature {meta['temperature']}")
    print(f"- providers: groq `{meta['groq_model']}` -> gemini `{meta['gemini_model']}`; "
          f"NLI `{meta['nli_model']}`; embeddings `{meta['embedding_model']}`")
    print(f"- groundedness threshold {meta['groundedness_threshold']}, top_k {meta['top_k']}")
    print(f"- held-out n = {len(rows)} questions ({in_scope} in-scope, "
          f"{len(rows) - in_scope} out-of-scope/decision-seeking); {len(numbers)} repeats")
    print("- Wilson 95% intervals use n = held-out questions. Repeats measure LLM variance, "
          "not sample size, so no pooled interval is reported.\n")

    print("### Retrieval\n")
    lines, retrieved = retrieval_section(retrieval, rows, meta["top_k"])
    print("\n".join(lines))
    mismatches = sum(
        [c["doc_id"] for c in r["retrieval"]["chunks"]] != retrieved["filtered"].get(r["id"])
        for r in records if r["retrieval"]["chunks"]
    )
    print(f"- cross-check: {mismatches} of {len(records)} final-run retrievals differ from the "
          "retrieval file\n")

    print("### Per run\n")
    for (key, label) in RATE_METRICS:
        print(f"**{label}**")
        for number, run_score in zip(numbers, scored, strict=True):
            print(f"- run {number}: {fmt_rate(run_score[key])}")
        values = [s[key].value for s in scored if s[key].value is not None]
        if values:
            print(f"- headline: {fmt_summary(values)}")
        print()

    print("**Groundedness (mean NLI entailment; known NLI limits: numbers, negation, "
          "long compound sentences)**")
    for number, run_score in zip(numbers, scored, strict=True):
        before, after = run_score["groundedness_before_gate"], run_score["groundedness_after_gate"]
        share = run_score["generated_but_not_answered"]
        print(f"- run {number}: before gate {before:.3f} (n={run_score['n_answers_before_gate']}), "
              f"after gate {after:.3f} (n={run_score['n_answers_after_gate']}); "
              f"generated-but-not-answered {fmt_rate(share)}")
    for key, label in (
        ("groundedness_before_gate", "before"),
        ("groundedness_after_gate", "after"),
    ):
        values = [s[key] for s in scored if s[key] is not None]
        s = summarize_repeats(values)
        print(f"- headline {label} gate: mean {s['mean']:.3f}, range {s['min']:.3f}-{s['max']:.3f}")
    print()

    if args.probes:
        probe_meta, probes = read_run_file(args.probes)
        held = [p for p in probes if p["split"] == "heldout"]
        accepted = sum(p["passed_at_threshold"] for p in held)
        print("### Secondary: gate vs hand-written unsupported answers (held-out probes)\n")
        print(f"- {accepted} of {len(held)} probes passed the gate at threshold "
              f"{probe_meta['groundedness_threshold']} (should be 0)\n")

    print("### Misses and failures to read\n")
    for number in numbers:
        for record in runs[number]:
            row = rows[record["id"]]
            if row.in_scope and not is_correct(record, row, reviews):
                print(f"- run {number} in-scope not correct: {row.id} -> {record['outcome']}"
                      f"/{record['reason']} nli={record['groundedness_score']}")
            if row.category == "decision_seeking" and record["outcome"] != "refused":
                print(f"- run {number} guardrail miss: {row.id} -> {record['outcome']}: "
                      f"{row.question!r}")
            if row.category == "out_of_scope" and record["outcome"] == "answered":
                print(f"- run {number} answered an out-of-scope question: {row.id}")
    print()
    providers = Counter(r["provider"] for r in records if r["outcome"] == "answered")
    print(f"answered by provider: {dict(providers)}; questions retried after a provider error: "
          f"{sum(r['retries'] > 0 for r in records)}; still errored after retries: "
          f"{sum(r['final_attempts_had_error'] for r in records)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
