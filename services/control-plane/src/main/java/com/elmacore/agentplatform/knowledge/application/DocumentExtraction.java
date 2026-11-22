package com.elmacore.agentplatform.knowledge.application;

import com.elmacore.agentplatform.knowledge.domain.TextChunking.Section;
import java.util.List;

public interface DocumentExtraction { List<Section> extract(String contentType,byte[] source); }
