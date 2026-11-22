import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    environment: str

    @classmethod
    def from_environment(cls) -> "Settings":
        environment = os.environ.get("APP_ENV", "production")
        if environment not in {"development", "test", "production", "pilot"}:
            raise ValueError("APP_ENV must be development, test, production, or pilot")
        return cls(environment=environment)
