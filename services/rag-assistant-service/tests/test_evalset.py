"""Eval-set loader/validator/splitter, plus integrity checks on the committed dataset files
(these run in CI even though the scored eval itself cannot: it needs Atlas, LLM keys and torch)."""

import json

import pytest

from evaluation.evalset import (
    CATEGORIES,
    EvalSetError,
    assign_splits,
    load_eval_set,
    load_probes,
    main,
    write_splits,
)


def row(row_id, category="factual", **overrides):
    in_scope = category in ("factual", "paraphrase", "hard")
    base = {
        "id": row_id,
        "question": f"question number {row_id}?",
        "category": category,
        "in_scope": in_scope,
        "expected_doc_id": "dental_coverage-limits" if in_scope else None,
        "key_facts": [["2000"]] if in_scope else [],
        "split": None,
        "note": "",
    }
    return {**base, **overrides}


def write(path, rows):
    path.write_text("\n".join(json.dumps(r) for r in rows) + "\n", encoding="utf-8")
    return path


# --- the committed dataset --------------------------------------------------------------------


def test_committed_eval_set_is_valid_and_split():
    rows = load_eval_set()
    assert {r.split for r in rows} == {"calibration", "heldout"}
    assert {r.category for r in rows} == set(CATEGORIES)


def test_committed_split_matches_the_seeded_assignment():
    rows = load_eval_set()
    expected = assign_splits(rows)
    assert {r.id: r.split for r in rows} == expected


def test_committed_probes_reference_in_scope_rows():
    probes = load_probes(load_eval_set())
    assert probes


# --- validation -------------------------------------------------------------------------------


def test_rejects_unknown_doc_id(tmp_path):
    path = write(tmp_path / "e.jsonl", [row("a", expected_doc_id="dental_nonsense")])
    with pytest.raises(EvalSetError, match="not a real docId"):
        load_eval_set(path, require_splits=False)


def test_rejects_duplicate_questions_ignoring_case_and_punctuation(tmp_path):
    rows = [row("a", question="What is the cap?"), row("b", question="what is the cap")]
    with pytest.raises(EvalSetError, match="duplicate questions"):
        load_eval_set(write(tmp_path / "e.jsonl", rows), require_splits=False)


def test_out_of_scope_rows_cannot_carry_expectations(tmp_path):
    path = write(tmp_path / "e.jsonl", [row("a", "out_of_scope", key_facts=[["x"]])])
    with pytest.raises(EvalSetError, match="need in_scope=false"):
        load_eval_set(path, require_splits=False)


def test_in_scope_rows_need_key_facts(tmp_path):
    path = write(tmp_path / "e.jsonl", [row("a", key_facts=[])])
    with pytest.raises(EvalSetError, match="need key_facts"):
        load_eval_set(path, require_splits=False)


def test_requires_splits_when_asked(tmp_path):
    path = write(tmp_path / "e.jsonl", [row("a")])
    with pytest.raises(EvalSetError, match="no split"):
        load_eval_set(path)


def test_probe_must_reference_an_in_scope_row(tmp_path):
    rows = load_eval_set(write(tmp_path / "e.jsonl", [row("a", "out_of_scope")]),
                         require_splits=False)
    probes = tmp_path / "p.jsonl"
    write(probes, [{"id": "p1", "eval_id": "a", "kind": "wrong_number", "answer": "x"}])
    with pytest.raises(EvalSetError, match="in-scope"):
        load_probes(rows, probes)


# --- split ------------------------------------------------------------------------------------


def _many_rows():
    rows = []
    for category, count in (("factual", 10), ("hard", 5), ("out_of_scope", 4),
                            ("decision_seeking", 3), ("paraphrase", 1)):
        rows += [row(f"{category}-{i}", category) for i in range(count)]
    return rows


def test_split_is_deterministic_and_stratified(tmp_path):
    path = write(tmp_path / "e.jsonl", _many_rows())
    parsed = load_eval_set(path, require_splits=False)

    first = assign_splits(parsed)
    assert first == assign_splits(parsed)
    for category in ("factual", "hard", "out_of_scope", "decision_seeking"):
        splits = {first[r.id] for r in parsed if r.category == category}
        assert splits == {"calibration", "heldout"}, category
    # A one-row category goes to held-out rather than being lost to calibration.
    assert first["paraphrase-0"] == "heldout"


def test_write_splits_persists_and_refuses_to_reassign(tmp_path, capsys):
    path = write(tmp_path / "e.jsonl", _many_rows())
    assignment = write_splits(path)
    assert {r.id: r.split for r in load_eval_set(path)} == assignment

    assert main(["assign", "--path", str(path)]) == 1
    assert "frozen" in capsys.readouterr().err
