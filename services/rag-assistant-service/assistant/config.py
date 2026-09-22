"""Runtime configuration, read from environment variables.

API keys live only in `.env` (gitignored) and reach the container via docker-compose's
`env_file`; nothing secret has a default here.
"""

import os
from dataclasses import dataclass


class _NonEmptyEnv:
    """`.get(key, default)` that also falls back to the default when the variable is set but
    empty (e.g. `GROQ_MODEL=` left blank in .env)."""

    def __init__(self, env) -> None:
        self._env = env

    def __getitem__(self, key: str) -> str:
        return self._env[key]

    def get(self, key: str, default: str = "") -> str:
        return self._env.get(key) or default


@dataclass(frozen=True)
class Settings:
    mongodb_uri: str
    claims_intake_url: str
    groq_api_key: str
    gemini_api_key: str
    groq_model: str
    gemini_model: str
    nli_model: str
    # 0.5 is NOT a calibrated value. Phase 7b's calibration (PLAN.md section 12, 2026-09-21) found
    # no threshold that rejects every unfaithful attempt on this corpus, so by the rule fixed
    # beforehand the provisional 0.5 was kept. At 0.5 the gate still accepted 4 of 12 adversarial
    # probes and scored 8 of 27 faithful answers under the threshold.
    groundedness_threshold: float
    retrieval_top_k: int
    provider_timeout_seconds: float

    @classmethod
    def from_env(cls) -> "Settings":
        env = _NonEmptyEnv(os.environ)
        return cls(
            mongodb_uri=env["MONGODB_URI"],
            claims_intake_url=env.get("CLAIMS_INTAKE_URL", "http://claims-intake-service:8082"),
            groq_api_key=env.get("GROQ_API_KEY", ""),
            gemini_api_key=env.get("GEMINI_API_KEY", ""),
            groq_model=env.get("GROQ_MODEL", "openai/gpt-oss-120b"),
            gemini_model=env.get("GEMINI_MODEL", "gemini-3.5-flash-lite"),
            nli_model=env.get("NLI_MODEL", "cross-encoder/nli-deberta-v3-small"),
            groundedness_threshold=float(env.get("GROUNDEDNESS_THRESHOLD", "0.5")),
            retrieval_top_k=int(env.get("RETRIEVAL_TOP_K", "3")),
            provider_timeout_seconds=float(env.get("PROVIDER_TIMEOUT_SECONDS", "20")),
        )
