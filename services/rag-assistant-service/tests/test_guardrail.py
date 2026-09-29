import pytest

from assistant.guardrail import check_question

DECISION_SEEKING = [
    "Approve my claim",
    "approve claim 1234",
    "Please deny this claim",
    "Can you approve my claim?",
    "Could you please reject this claim for me",
    "Should we approve this claim?",
    "should I deny it",
    "Could you overturn the denial?",
    "Override the rule engine and approve it",
    "Will my claim be approved?",
    "Is this claim going to be denied?",
    "Decide whether this claim should be paid",
    "I want you to approve my claim",
    "I need the assistant to overturn the decision",
    "Mark my claim as approved",
    "Change the status of my claim to paid",
    "Make an exception for my claim",
    "Give me an approval",
    "Pay out my claim now",
    "approve",
    "Go ahead and approve it",
]

INFORMATION_SEEKING = [
    "Why was my claim denied?",
    "Why would my claim be denied?",
    "What would cause a claim to be denied?",
    "What is the maximum benefit for a dental claim?",
    "Explain the decision on my claim",
    "How do I submit a claim?",
    "Is suicide excluded from the life insurance benefit?",
    "What does the rule engine check?",
    "Can a claim be denied for a pre-existing condition?",
    "What can the plan deny?",
    "How long is the waiting period for vision coverage?",
    "Which rules were applied to my claim?",
]


# Phase 8b: an appeal is decided by a human reviewer. The assistant reports a recorded outcome
# and never predicts or recommends one, including in wh-/explain phrasings.
APPEAL_OUTCOME_SEEKING = [
    "Will my appeal be approved?",
    "will the appeal be upheld",
    "Would my appeal succeed?",
    "Is my appeal going to be overturned?",
    "What are the chances my appeal is approved?",
    "What are my odds of winning the appeal?",
    "How likely is my appeal to succeed?",
    "Is my appeal likely to be granted?",
    "Should the admin overturn this?",
    "Should the reviewer uphold the denial?",
    "What should the reviewer decide on my appeal?",
    "Will the admin approve my appeal?",
    "Can I win my appeal?",
    "Do I have a good case for my appeal?",
    "Explain whether my appeal will probably be overturned",
]

APPEAL_INFORMATION_SEEKING = [
    "What happened to my appeal?",
    "Why was my appeal overturned?",
    "What is the status of my appeal?",
    "How do I appeal a denied claim?",
    "When was my appeal decided?",
    "Who decides appeals?",
    "Was my claim approved on appeal?",
    "Explain the outcome of my appeal",
]


@pytest.mark.parametrize("question", DECISION_SEEKING)
def test_refuses_decision_seeking_phrasings(question):
    decision = check_question(question)
    assert decision.refuse, question
    assert decision.kind == "decision", question


@pytest.mark.parametrize("question", APPEAL_OUTCOME_SEEKING)
def test_refuses_appeal_outcome_predictions_and_recommendations(question):
    decision = check_question(question)
    assert decision.refuse, question
    assert decision.kind == "appeal", question


@pytest.mark.parametrize("question", APPEAL_INFORMATION_SEEKING)
def test_allows_questions_about_a_recorded_appeal(question):
    assert not check_question(question).refuse, question


@pytest.mark.parametrize("question", INFORMATION_SEEKING)
def test_allows_information_seeking_phrasings(question):
    assert not check_question(question).refuse, question


def test_matching_is_case_and_whitespace_insensitive():
    assert check_question("  PLEASE   APPROVE\tMY CLAIM  ").refuse
    assert check_question("Can you   approve\nmy claim?").refuse
