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


@pytest.mark.parametrize("question", DECISION_SEEKING)
def test_refuses_decision_seeking_phrasings(question):
    assert check_question(question).refuse, question


@pytest.mark.parametrize("question", INFORMATION_SEEKING)
def test_allows_information_seeking_phrasings(question):
    assert not check_question(question).refuse, question


def test_matching_is_case_and_whitespace_insensitive():
    assert check_question("  PLEASE   APPROVE\tMY CLAIM  ").refuse
    assert check_question("Can you   approve\nmy claim?").refuse
