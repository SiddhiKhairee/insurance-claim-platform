"""Builds the real (model-loading, network-connected) Assistant from environment settings.

Kept separate from `graph.py` so tests can build an Assistant from fakes without importing
torch or opening a Mongo connection.
"""

from pymongo import MongoClient

from assistant.claims_client import HttpClaimsClient
from assistant.config import Settings
from assistant.graph import Assistant, GraphDeps
from assistant.groundedness import CrossEncoderNLI, GroundednessGate
from assistant.llm import GeminiProvider, GroqProvider
from assistant.retrieval import MongoVectorStore, SentenceTransformerEmbedder

DATABASE_NAME = "claims_platform"
COLLECTION_NAME = "policy_documents"


def build_deps(settings: Settings) -> GraphDeps:
    """The real dependency wiring. Public so the Phase 7b eval harness measures exactly this
    wiring (wrapped with recorders) instead of a copy that could drift."""
    collection = MongoClient(settings.mongodb_uri)[DATABASE_NAME][COLLECTION_NAME]
    return GraphDeps(
        embedder=SentenceTransformerEmbedder(),
        store=MongoVectorStore(collection),
        claims=HttpClaimsClient(settings.claims_intake_url),
        # Provider chain order: Groq -> Gemini -> (graph's deterministic abstain).
        providers=[
            GroqProvider(settings.groq_api_key, settings.groq_model,
                         settings.provider_timeout_seconds),
            GeminiProvider(settings.gemini_api_key, settings.gemini_model,
                           settings.provider_timeout_seconds),
        ],
        gate=GroundednessGate(CrossEncoderNLI(settings.nli_model),
                              settings.groundedness_threshold),
        top_k=settings.retrieval_top_k,
    )


def build_assistant(settings: Settings) -> Assistant:
    return Assistant(build_deps(settings))
