from decimal import Decimal

import pytest
from fakes import DENIED_CLAIM, DENTAL_EXCLUSIONS, DENTAL_LIMITS, FakeNLI

from assistant.groundedness import GroundednessGate, extract_numbers, split_sentences
from assistant.premises import premise_from_chunk, premise_from_claim
from assistant.schemas import ClaimCitation, PolicyCitation


def numbers(text, **kwargs):
    return extract_numbers(text, **kwargs)


class TestExtractNumbers:
    def test_normalizes_currency_and_commas(self):
        assert numbers("$5,000") == numbers("5000") == numbers("5,000.00") == [Decimal("5000")]

    def test_decimals_and_percentages(self):
        assert numbers("pays 80% up to $1,234.50") == [Decimal("80"), Decimal("1234.50")]

    def test_trailing_sentence_punctuation_is_not_part_of_the_number(self):
        assert numbers("The limit is $2,000.") == [Decimal("2000")]

    def test_ordinals_and_glued_tokens_are_ignored(self):
        assert numbers("the 1st, 2nd and 3rd claim, 4th year, abc123") == []

    def test_leading_list_markers_are_ignored(self):
        text = "1. Dental pays $2,000.\n2) Vision pays $500.\n- Life pays $10,000."
        assert numbers(text, ignore_labels=True) == [
            Decimal("2000"), Decimal("500"), Decimal("10000"),
        ]

    def test_labelled_references_are_ignored(self):
        text = "Under Section 3, Article 4.2 and Step 2 the limit is $2,000."
        assert numbers(text, ignore_labels=True) == [Decimal("2000")]

    def test_real_numbers_next_to_labels_are_kept(self):
        assert numbers("Section 3 allows 30 days.", ignore_labels=True) == [Decimal("30")]

    def test_without_ignore_labels_markers_are_numbers(self):
        assert Decimal("1") in numbers("1. Dental pays $2,000.")


class TestSplitSentences:
    def test_keeps_currency_decimals_together(self):
        text = "The plan pays up to $10,000.00 per claim. Claims above that are denied."
        assert split_sentences(text) == [
            "The plan pays up to $10,000.00 per claim.",
            "Claims above that are denied.",
        ]

    def test_splits_bullets_and_strips_markers(self):
        assert split_sentences("1. Dental pays $2,000.\n2. Vision pays $500.") == [
            "Dental pays $2,000.",
            "Vision pays $500.",
        ]

    def test_empty(self):
        assert split_sentences("  \n ") == []


LIMITS = premise_from_chunk(DENTAL_LIMITS)
EXCLUSIONS = premise_from_chunk(DENTAL_EXCLUSIONS)
CLAIM = premise_from_claim(DENIED_CLAIM)


def gate(nli=None, threshold=0.5):
    return GroundednessGate(nli or FakeNLI(), threshold)


class TestNumericCheck:
    def test_fabricated_number_fails_even_with_high_nli_score(self):
        result = gate(FakeNLI(score=0.99)).evaluate(
            "The Group Dental Plan pays up to $50,000 per claim.", [LIMITS]
        )
        assert result.nli_score == 0.99
        assert not result.numbers_supported
        assert result.unsupported_numbers == ["50000"]
        assert not result.passed

    def test_correct_number_in_a_different_format_passes(self):
        result = gate().evaluate("The Group Dental Plan pays up to 2000 per claim.", [LIMITS])
        assert result.numbers_supported
        assert result.passed

    def test_number_supported_only_by_the_claim_record_passes(self):
        result = gate().evaluate(
            "The requested amount was $2,500 and the plan-limit check failed.", [LIMITS, CLAIM]
        )
        assert result.numbers_supported
        assert result.passed

    def test_answer_without_numbers_passes_the_numeric_check(self):
        result = gate().evaluate("The Group Dental Plan excludes teeth whitening.", [EXCLUSIONS])
        assert result.numbers_supported
        assert result.unsupported_numbers == []

    def test_check_is_answer_level_a_number_only_needs_to_be_in_some_premise(self):
        # $2,500 appears only in the claim record, but the sentence using it is about the
        # exclusions premise. The numeric check accepts it (answer-level); pairing numbers with
        # the right sentence is the NLI check's job.
        result = gate().evaluate(
            "The Group Dental Plan excludes whitening for $2,500.", [EXCLUSIONS, CLAIM]
        )
        assert result.numbers_supported

    def test_list_markers_and_labels_in_the_answer_do_not_trip_the_check(self):
        result = gate().evaluate(
            "1. The Group Dental Plan pays up to $2,000 per claim (Section 3).", [LIMITS]
        )
        assert result.numbers_supported
        assert result.passed


class TestNliCheck:
    def test_below_threshold_fails(self):
        result = gate(FakeNLI(score=0.2), threshold=0.5).evaluate(
            "The Group Dental Plan excludes teeth whitening.", [EXCLUSIONS]
        )
        assert result.numbers_supported
        assert result.nli_score == 0.2
        assert not result.passed

    def test_answer_score_is_min_over_sentences_of_max_over_premises(self):
        def score(premise, hypothesis):
            if "whitening" in hypothesis:
                return 0.9 if "whitening" in premise else 0.1
            return 0.95 if "$2,000" in premise else 0.3

        answer = (
            "The Group Dental Plan pays up to $2,000 per claim. "
            "The Group Dental Plan excludes teeth whitening."
        )
        result = gate(FakeNLI(score_fn=score)).evaluate(answer, [LIMITS, EXCLUSIONS])
        assert result.nli_score == pytest.approx(0.9)
        assert result.passed

    def test_one_unsupported_sentence_fails_the_whole_answer(self):
        def score(premise, hypothesis):
            return 0.05 if "pilates" in hypothesis else 0.95

        answer = "The Group Dental Plan excludes teeth whitening. The plan covers pilates."
        result = gate(FakeNLI(score_fn=score)).evaluate(answer, [EXCLUSIONS])
        assert result.nli_score == pytest.approx(0.05)
        assert not result.passed

    def test_every_sentence_is_scored_against_every_premise_individually(self):
        nli = FakeNLI()
        gate(nli).evaluate("Sentence one is here. Sentence two is here.", [LIMITS, EXCLUSIONS])
        assert len(nli.pairs) == 4
        assert {premise for premise, _ in nli.pairs} == {LIMITS.text, EXCLUSIONS.text}

    def test_empty_answer_or_no_premises_fails(self):
        assert not gate().evaluate("", [LIMITS]).passed
        assert not gate().evaluate("The plan pays.", []).passed


class TestCitations:
    def test_policy_and_claim_citations_are_tagged(self):
        def score(premise, hypothesis):
            if "requested" in hypothesis:
                return 0.9 if premise.startswith("Claim record") else 0.1
            return 0.9 if "$2,000.00" in premise else 0.1

        answer = (
            "The Group Dental Plan pays up to $2,000 per claim. "
            "The requested amount was $2,500 for this claim."
        )
        result = gate(FakeNLI(score_fn=score)).evaluate(answer, [LIMITS, CLAIM])
        assert result.citations == [
            PolicyCitation(
                docId="dental_coverage-limits", planType="dental",
                section="Coverage Limits", score=0.85,
            ),
            ClaimCitation(claimId="claim-1"),
        ]
        assert result.citations[1].model_dump() == {"source": "claim", "claimId": "claim-1"}
        assert result.citations[0].model_dump()["source"] == "policy"

    def test_citations_are_deduplicated(self):
        result = gate().evaluate(
            "The Group Dental Plan pays claims. The Group Dental Plan has a limit.", [LIMITS]
        )
        assert len(result.citations) == 1
