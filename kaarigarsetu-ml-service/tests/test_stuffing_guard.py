"""Keyword stuffing: reciting the rubric's words (in every language and spelling) must reach a human, honest answers must not be bothered."""
import pytest

from app.services.evaluation_service import EvaluationService
from app.services.guard import _distinct_forms, stuffing_groups, stuffing_reasons
from app.services.rubric_store import available_trades, load_rubric

SERVICE = EvaluationService()


def all_questions():
    for trade in [t['key'] for t in available_trades()]:
        rubric = load_rubric(trade)
        for question in rubric.questions:
            yield pytest.param(trade, question, id=f"{trade}-q{question.id}")


@pytest.mark.parametrize("trade,question", all_questions())
def test_reciting_every_rubric_word_is_flagged_whenever_a_group_has_enough_words_to_recite(trade, question):
    recited = " ".join(k for c in question.criteria for k in c.keywords)
    assert bool(stuffing_reasons(recited, question)) == bool(stuffing_groups(question))


@pytest.mark.parametrize("trade,question", all_questions())
def test_reciting_only_the_questions_own_topic_words_is_flagged_when_there_are_enough_of_them(trade, question):
    recited = " ".join(k for c in question.criteria if not c.requires_topic for k in c.keywords)
    topic = _distinct_forms(k for c in question.criteria if not c.requires_topic for k in c.keywords)
    if len(topic) >= 7:
        assert stuffing_reasons(recited, question), f"{trade} q{question.id}: {len(topic)} topic words recited but not flagged"


@pytest.mark.parametrize("trade,question", all_questions())
def test_an_honest_answer_naming_one_word_per_idea_is_never_flagged(trade, question):
    honest = "मैं " + ", ".join(c.keywords[0] for c in question.criteria) + " का इस्तेमाल करता हूँ।"
    assert stuffing_reasons(honest, question) == []


@pytest.mark.parametrize("trade,question", all_questions())
def test_naming_each_idea_twice_in_two_languages_is_still_not_flagged(trade, question):
    twice = " ".join(" ".join(c.keywords[:2]) for c in question.criteria[:3])
    assert stuffing_reasons(twice, question) == []


def test_the_recited_list_reaches_a_human_through_the_service_and_keeps_the_score_visible():
    question = load_rubric("electrician").question(3)
    recited = " ".join(k for c in question.criteria for k in c.keywords)
    result = SERVICE.evaluate("electrician", recited, 3, "hi")
    if stuffing_groups(question):
        assert result["needs_review"] is True and "keyword stuffing" in result["review_reason"]


def test_the_real_spoken_style_answer_to_question_one_is_not_flagged():
    result = SERVICE.evaluate("electrician", "मैं रोज पेचकस प्लायर और टेस्टर का इस्तेमाल करता हूँ।", 1, "hi")
    assert result["needs_review"] is False and result["review_reason"] is None


def test_nested_spellings_count_once():
    assert _distinct_forms(["plier", "pliers", "Pliers"]) == ["pliers"]
