package com.elmacore.agentplatform.knowledge.application;

import com.elmacore.agentplatform.knowledge.domain.EmbeddingVector;
import java.util.List;

public interface Embeddings { List<EmbeddingVector> embed(String kind,List<String> texts); }
