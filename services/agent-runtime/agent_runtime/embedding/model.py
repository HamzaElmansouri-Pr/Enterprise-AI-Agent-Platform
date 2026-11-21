"""Fixed, offline-only CPU embedding adapter. Setup is a separate operator action."""

import hashlib
import json
from pathlib import Path

MODEL = "intfloat/multilingual-e5-small"
REVISION = "614241f622f53c4eeff9890bdc4f31cfecc418b3"
MODEL_ID = f"{MODEL}@{REVISION}"
FILES = (
    "model.onnx",
    "config.json",
    "tokenizer.json",
    "tokenizer_config.json",
    "special_tokens_map.json",
)


class LocalEmbeddings:
    def __init__(self, directory: str):
        self.directory = Path(directory)
        self.model = None

    def _load(self):
        # A setup manifest pins every byte; startup never invokes a download API.
        manifest = json.loads((self.directory / "manifest.json").read_text())
        if manifest["modelId"] != MODEL_ID:
            raise ValueError("Unavailable model")
        for name in FILES:
            path = self.directory / name
            with path.open("rb") as stream:
                digest = hashlib.file_digest(stream, "sha256").hexdigest()
            if manifest["sha256"][name] != digest:
                raise ValueError("Unavailable model")
        from fastembed import TextEmbedding
        from fastembed.common.model_description import ModelSource, PoolingType

        name = "eaap/pinned-multilingual-e5-small"
        if not any(m["model"] == name for m in TextEmbedding.list_supported_models()):
            TextEmbedding.add_custom_model(
                model=name,
                pooling=PoolingType.MEAN,
                normalization=True,
                sources=ModelSource(hf=MODEL),
                dim=384,
                model_file="model.onnx",
                output_name="last_hidden_state",
            )
        self.model = TextEmbedding(
            model_name=name,
            specific_model_path=str(self.directory),
            local_files_only=True,
            threads=2,
            providers=["CPUExecutionProvider"],
            cuda=False,
        )

    def embed(self, kind: str, texts: list[str]) -> list[list[float]]:
        if self.model is None:
            self._load()
        values = [f"{kind}: {text}" for text in texts]
        return [v.tolist() for v in self.model.embed(values, batch_size=8)]
