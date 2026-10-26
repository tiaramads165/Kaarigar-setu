"""Integrity of the rubric DATA: every trade loads, has real depth, and the shared measures cannot be triggered by accident."""
import json

import pytest
from fastapi.testclient import TestClient

from app.main import app
from app.services import rubric_store
from app.services.guard import gate_relevance
from app.services.rubric_evaluator import RubricEvaluator, contains_keyword, fold
from app.services.rubric_store import available_trades, load_rubric

client = TestClient(app)
TRADES = ["carpenter", "electrician", "mason", "painter", "plumber", "welder"]


def test_every_trade_has_a_rubric_file_and_nothing_else_is_listed():
    assert [t["key"] for t in available_trades()] == TRADES
    assert all(t["test_only"] for t in available_trades()), "until the real rubrics arrive every shipped rubric must say TEST ONLY"
    assert client.get("/trades").json()["trades"] == available_trades()


@pytest.mark.parametrize("trade", TRADES)
def test_each_rubric_has_ten_bilingual_questions_with_enough_marking_criteria(trade):
    rubric = load_rubric(trade)
    assert rubric.test_only and len(rubric.questions) == 10
    for question in rubric.questions:
        assert set(question.text) >= {"hi", "en"} and all(question.text.values())
        own = [c for c in question.criteria if not c.requires_topic]
        shared = [c for c in question.criteria if c.requires_topic]
        assert len(own) >= 2 and len(shared) == 3, f"{trade} q{question.id}"
        assert len({c.id for c in question.criteria}) == len(question.criteria), "criterion ids must be unique"
        assert all(c.marks >= 1 and c.keywords for c in question.criteria)
        assert question.max_marks == sum(c.marks for c in question.criteria)


@pytest.mark.parametrize("trade", TRADES)
def test_the_public_rubric_view_still_hides_every_grader_only_detail(trade):
    text = json.dumps(client.get(f"/rubric/{trade}", params={"language": "hi"}).json(), ensure_ascii=False)
    for secret in ("keywords", "criteria", "ideal_notes", "requires_topic", "cm1", "TEST ONLY."):
        assert secret not in text


def test_shared_measures_cannot_be_triggered_by_a_trades_own_topic_words():
    """A tool called 'tester' must not count as 'mentions testing': no shared-measure keyword may match inside any topic keyword."""
    clashes = []
    for trade in TRADES:
        for question in load_rubric(trade).questions:
            for shared in (c for c in question.criteria if c.requires_topic):
                for topic in (c for c in question.criteria if not c.requires_topic):
                    for shared_word in shared.keywords:
                        for topic_word in topic.keywords:
                            if contains_keyword(fold(topic_word), fold(shared_word)):
                                clashes.append(f"{trade} q{question.id}: shared {shared_word!r} matches topic word {topic_word!r}")
    assert clashes == []


def test_an_off_topic_answer_cannot_collect_the_shared_marks():
    sentence = "जैसे पहले मैंने सावधानी से क्रिकेट देखा और फिर जांच की, first I was careful, then I check the match"
    for trade in TRADES:
        result = client.post("/evaluate", json={"trade": trade, "transcript": sentence, "question_id": 1, "language": "hi"}).json()
        assert result["score"] == 0.0, trade


def test_the_gate_keeps_shared_marks_when_the_topic_is_covered_and_drops_them_when_not():
    question = load_rubric("carpenter").question(1)
    ids = [c.id for c in question.criteria]
    shared_only = {i: (1 if i.startswith("q1cm") else 0) for i in ids}
    assert sum(gate_relevance(shared_only, question).values()) == 0
    with_topic = {**shared_only, "q1c1": 2}
    assert gate_relevance(with_topic, question) == with_topic


def test_an_answer_on_topic_with_an_example_care_and_a_check_earns_the_extra_marks():
    answer = "मैं आरी और हथौड़ा और छेनी इस्तेमाल करता हूं। जैसे पहले माप लेता हूं, सावधानी रखता हूं और बाद में जांच करता हूं।"
    result = client.post("/evaluate", json={"trade": "carpenter", "transcript": answer, "question_id": 1, "language": "hi"}).json()
    assert result["score"] == 100.0 and [c["marks"] for c in result["criteria_scores"]] == [2, 2, 2, 1, 1, 1]


@pytest.mark.parametrize("trade,transcript", [
    ("carpenter", "I use a saw, a hammer and a chisel every day"),
    ("mason", "I use a trowel, a spirit level and a shovel every day"),
    ("welder", "I use a welding machine, electrodes and a grinder every day"),
    ("painter", "I use a brush, a roller and sandpaper every day"),
])
def test_each_new_trades_tools_question_recognises_its_own_tools(trade, transcript):
    result = client.post("/evaluate", json={"trade": trade, "transcript": transcript, "question_id": 1, "language": "en"}).json()
    assert [c["marks"] for c in result["criteria_scores"]][:3] == [2, 2, 2]


def test_unrelated_answers_score_zero_for_every_trade_and_question():
    for trade in TRADES:
        for question in load_rubric(trade).questions:
            result = client.post("/evaluate", json={"trade": trade, "transcript": "my favourite food is rice and I like cricket",
                                                    "question_id": question.id, "language": "en"}).json()
            assert result["score"] == 0.0, (trade, question.id)


def test_the_legacy_whole_transcript_scorer_knows_every_trade():
    evaluator = RubricEvaluator()
    assert set(evaluator.RUBRICS) == set(TRADES)


def test_a_rubric_can_opt_out_of_the_shared_measures_and_a_test_only_set_is_refused_in_production(tmp_path, monkeypatch):
    rubric = json.loads((rubric_store.RUBRIC_DIR / "welder.json").read_text(encoding="utf-8"))
    (tmp_path / "welder.json").write_text(json.dumps({**rubric, "commonMeasures": False}), encoding="utf-8")
    (tmp_path / "_common.json").write_text((rubric_store.RUBRIC_DIR / "_common.json").read_text(encoding="utf-8"), encoding="utf-8")
    monkeypatch.setattr(rubric_store, "RUBRIC_DIR", tmp_path)
    rubric_store.load_rubric.cache_clear()
    try:
        assert all(not c.requires_topic for q in rubric_store.load_rubric("welder").questions for c in q.criteria)
        rubric_store.load_rubric.cache_clear()
        (tmp_path / "welder.json").write_text(json.dumps({**rubric, "testOnly": False}), encoding="utf-8")
        monkeypatch.setenv("APP_ENV", "prod")
        with pytest.raises(RuntimeError, match="_common.json"):          # even a real rubric refuses to mix in TEST ONLY shared measures in production
            rubric_store.load_rubric("welder")
    finally:
        rubric_store.load_rubric.cache_clear()


@pytest.mark.parametrize("trade", TRADES)
def test_every_answer_key_works_an_answer_built_from_each_criterions_keywords_earns_the_full_topic_marks(trade):
    for question in load_rubric(trade).questions:
        own = [c for c in question.criteria if not c.requires_topic]
        answer = " ".join(c.keywords[0] for c in own)
        result = client.post("/evaluate", json={"trade": trade, "transcript": answer, "question_id": question.id, "language": "hi"}).json()
        earned = {c["id"]: c["marks"] for c in result["criteria_scores"]}
        assert all(earned[c.id] == c.marks for c in own), f"{trade} q{question.id}: {earned}"


@pytest.mark.parametrize("trade", TRADES)
def test_the_assessment_mixes_difficulty_levels_and_harder_questions_carry_more_marks(trade):
    questions = load_rubric(trade).questions
    levels = [q.level for q in questions]
    assert levels[:5] == ["basic"] * 5 and set(levels[5:]) == {"intermediate", "advanced"}
    advanced_marks = {c.marks for q in questions if q.level == "advanced" for c in q.criteria if not c.requires_topic}
    assert advanced_marks == {3}, "advanced questions are worth more per criterion"


@pytest.mark.parametrize("trade", TRADES)
def test_no_two_questions_of_a_trade_share_text_and_every_unvalidated_draft_says_so(trade):
    questions = load_rubric(trade).questions
    assert len({q.text["en"] for q in questions}) == 10 and len({q.text["hi"] for q in questions}) == 10
    for q in questions[5:]:
        assert "UNVALIDATED DRAFT" in q.ideal_notes
    raw = json.loads((rubric_store.RUBRIC_DIR / f"{trade}.json").read_text(encoding="utf-8"))
    assert "UNVALIDATED DRAFT" in raw["reviewStatus"]


@pytest.mark.parametrize("trade", TRADES)
def test_every_question_belongs_to_a_known_skill_area_and_the_public_view_exposes_only_level_and_area(trade):
    from app.services.rubric_store import AREAS
    rubric = load_rubric(trade)
    assert all(q.area in AREAS for q in rubric.questions)
    assert len({q.area for q in rubric.questions}) >= 4, "a breakdown needs several areas to be informative"
    assert [q.area for q in rubric.questions[:5]] == ["tools", "materials", "experience", "knowledge", "customer"]
    view = client.get(f"/rubric/{trade}", params={"language": "en"}).json()["questions"]
    assert all(set(q) == {"id", "text", "max_marks", "level", "area"} for q in view)
