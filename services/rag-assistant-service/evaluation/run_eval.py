"""Run the eval against the REAL stack (Atlas vector search, hosted LLMs, local NLI model).

    python -m evaluation.run_eval --mode retrieval --split heldout
    python -m evaluation.run_eval --mode probes    --split calibration
    python -m evaluation.run_eval --mode collect   --split calibration
    python -m evaluation.run_eval --mode final     --split heldout --repeats 3

Modes
  retrieval  no LLM. Per question: the graph's own retrieval (plan filter as `infer_plan_type`
             would set it) and an unfiltered search. Deterministic, so run ONCE, not per repeat.
  probes     no LLM. Feeds each hand-written unsupported answer through the real gate against the
             question's real retrieved chunks.
  collect    calibration data. Runs the real graph with a gate that records but always rejects, so
             every provider's answer and NLI score is captured for every question.
  final      the scored run: the real graph, real gate, at the configured threshold.

Needs MONGODB_URI (all modes) and GROQ_API_KEY / GEMINI_API_KEY (collect, final) in the
environment or in --env-file, plus the ML deps (requirements-ml.txt). Secrets are never printed.
"""

import argparse
import os
import time
from pathlib import Path

from assistant.config import Settings
from assistant.premises import premise_from_chunk
from assistant.retrieval import infer_plan_type
from evaluation.evalset import (
    DEFAULT_EVAL_PATH,
    DEFAULT_PROBES_PATH,
    EvalRow,
    load_eval_set,
    load_probes,
)
from evaluation.recording import Trace, instrument
from evaluation.runfiles import (
    RUNS_DIR,
    fingerprint,
    git_head,
    utc_now,
    write_jsonl,
)

RETRY_SLEEP_SECONDS = 30
MAX_RETRIES = 2
# Hard-coded in the request bodies of assistant/llm.py (Groq `temperature`, Gemini
# `generationConfig.temperature`); tests/test_llm.py asserts it. Logged with every run.
LLM_TEMPERATURE = 0


def load_env_file(path: Path) -> None:
    """Minimal KEY=VALUE loader (no python-dotenv dependency). Never overrides the environment."""
    if not path.exists():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        os.environ.setdefault(key.strip(), value.strip().strip("'\""))


def select_rows(rows: list[EvalRow], split: str) -> list[EvalRow]:
    return rows if split == "all" else [row for row in rows if row.split == split]


def retrieve_like_graph(deps, question: str, plan_type: str | None):
    """The graph's retrieve step (graph.py) without a claim: embed, search, top_k."""
    return deps.store.search(deps.embedder.embed(question), deps.top_k, plan_type)


def chunk_dicts(chunks) -> list[dict]:
    return [
        {"doc_id": c.doc_id, "score": c.score, "text": c.text} for c in chunks
    ]


def run_retrieval(deps, rows: list[EvalRow]) -> list[dict]:
    records = []
    for row in rows:
        plan = infer_plan_type(row.question)
        records.append({
            "id": row.id,
            "filtered": {"plan_type": plan,
                         "chunks": chunk_dicts(retrieve_like_graph(deps, row.question, plan))},
            "unfiltered": {"plan_type": None,
                           "chunks": chunk_dicts(retrieve_like_graph(deps, row.question, None))},
        })
    return records


def run_probes(deps, rows: list[EvalRow], threshold: float) -> list[dict]:
    by_id = {row.id: row for row in rows}
    records = []
    for probe in load_probes(rows, DEFAULT_PROBES_PATH):
        row = by_id[probe.eval_id]
        chunks = retrieve_like_graph(deps, row.question, infer_plan_type(row.question))
        result = deps.gate.evaluate(probe.answer, [premise_from_chunk(c) for c in chunks])
        records.append({
            "probe_id": probe.id, "eval_id": probe.eval_id, "kind": probe.kind,
            "split": row.split, "answer": probe.answer,
            "nli_score": result.nli_score, "numbers_supported": result.numbers_supported,
            "unsupported_numbers": result.unsupported_numbers,
            "passed_at_threshold": result.passed, "threshold": threshold,
        })
    return records


def ask_recorded(assistant, trace: Trace, row: EvalRow, run: int, sleep: float) -> dict:
    """Ask one question; if a provider errored (typically a free-tier rate limit), wait and ask
    again so an infrastructure hiccup isn't scored as an abstention. Retries are recorded."""
    retries = 0
    while True:
        trace.reset()
        response = assistant.ask(row.question)
        errored = any(a.kind == "error" for a in trace.attempts)
        if not errored or retries >= MAX_RETRIES:
            break
        retries += 1
        time.sleep(RETRY_SLEEP_SECONDS)
    time.sleep(sleep)
    return {
        "run": run,
        "id": row.id,
        "outcome": response.outcome,
        "reason": response.reason,
        "provider": response.provider,
        "answer": response.answer,
        "groundedness_score": response.groundedness_score,
        "numbers_supported": response.numbers_supported,
        "retrieval": {"plan_type": trace.plan_type, "chunks": chunk_dicts(trace.chunks)},
        "attempts": [a.to_dict() for a in trace.attempts],
        "retries": retries,
        "final_attempts_had_error": errored,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--mode", required=True,
                        choices=["retrieval", "probes", "collect", "final"])
    parser.add_argument("--split", required=True, choices=["calibration", "heldout", "all"])
    parser.add_argument("--repeats", type=int, default=1)
    parser.add_argument("--sleep", type=float, default=2.0, help="seconds between questions")
    parser.add_argument("--out", type=Path)
    parser.add_argument("--env-file", type=Path,
                        default=Path(__file__).resolve().parents[3] / ".env")
    args = parser.parse_args(argv)

    load_env_file(args.env_file)
    # Imported here so `--help` and the pure modules never need torch.
    from assistant.graph import Assistant
    from assistant.runtime import build_deps

    settings = Settings.from_env()
    rows = load_eval_set(DEFAULT_EVAL_PATH)
    selected = select_rows(rows, args.split)
    deps = build_deps(settings)

    meta = {
        "date": utc_now(), "commit": git_head(), "mode": args.mode, "split": args.split,
        "repeats": args.repeats if args.mode in ("collect", "final") else 1,
        "questions": len(selected),
        "temperature": LLM_TEMPERATURE,
        "groq_model": settings.groq_model, "gemini_model": settings.gemini_model,
        "nli_model": settings.nli_model, "embedding_model": "all-MiniLM-L6-v2",
        "groundedness_threshold": settings.groundedness_threshold,
        "top_k": settings.retrieval_top_k, "fingerprint": fingerprint(),
    }

    if args.mode == "retrieval":
        records = run_retrieval(deps, selected)
    elif args.mode == "probes":
        records = run_probes(deps, rows, settings.groundedness_threshold)
        records = [r for r in records if args.split == "all" or r["split"] == args.split]
    else:
        wrapped, trace = instrument(deps, always_reject=args.mode == "collect")
        assistant = Assistant(wrapped)
        repeats = 1 if args.mode == "collect" else args.repeats
        records = []
        for run in range(1, repeats + 1):
            for row in selected:
                records.append(ask_recorded(assistant, trace, row, run, args.sleep))
                print(f"run {run} {row.id}: {records[-1]['outcome']}", flush=True)

    out = args.out or RUNS_DIR / f"{utc_now()[:10]}-{args.mode}-{args.split}.jsonl"
    write_jsonl(out, meta, records)
    print(f"wrote {len(records)} records to {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
