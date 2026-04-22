from __future__ import annotations

from dataclasses import dataclass
from difflib import SequenceMatcher
from typing import Iterable, List
import logging

from .models import DownstreamTool, SearchHit

logger = logging.getLogger(__name__)


@dataclass(slots=True)
class SemanticMatcher:
    nlp: object | None
    model_name: str | None

    @classmethod
    def create(cls) -> "SemanticMatcher":
        try:
            import spacy  # type: ignore
        except Exception:
            logger.info("spaCy not available; using lexical fallback matcher")
            return cls(nlp=None, model_name=None)

        preferred_models = [
            "en_core_web_md",
            "en_core_web_lg",
            "en_core_web_sm",
        ]
        for model_name in preferred_models:
            try:
                return cls(nlp=spacy.load(model_name), model_name=model_name)
            except Exception:
                continue

        logger.info("No spaCy English model available; using blank English tokenizer")
        try:
            return cls(nlp=spacy.blank("en"), model_name="blank_en")
        except Exception:
            return cls(nlp=None, model_name=None)

    def score(self, query: str, text: str) -> float:
        query = (query or "").strip()
        text = (text or "").strip()
        if not query or not text:
            return 0.0
        if self.nlp is not None:
            try:
                qdoc = self.nlp(query)
                tdoc = self.nlp(text)
                semantic = float(qdoc.similarity(tdoc))
            except Exception:
                semantic = 0.0
        else:
            semantic = 0.0

        lexical = SequenceMatcher(None, query.lower(), text.lower()).ratio()
        if semantic <= 0.0:
            return lexical
        return max(semantic, lexical * 0.85)

    def rank(self, query: str, tools: Iterable[DownstreamTool], limit: int = 5) -> List[SearchHit]:
        hits: List[SearchHit] = []
        for tool in tools:
            haystack = " | ".join(
                [
                    tool.path,
                    tool.tool_name,
                    tool.title or "",
                    tool.description,
                    " ".join(tool.tags),
                ]
            )
            score = self.score(query, haystack)
            if score > 0:
                hits.append(SearchHit(tool=tool, score=score, reason="semantic_similarity"))
        hits.sort(key=lambda item: item.score, reverse=True)
        return hits[:limit]
