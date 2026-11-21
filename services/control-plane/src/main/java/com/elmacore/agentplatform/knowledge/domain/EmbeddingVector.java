package com.elmacore.agentplatform.knowledge.domain;

import java.util.List;

public final class EmbeddingVector {
    public static final String MODEL="intfloat/multilingual-e5-small@614241f622f53c4eeff9890bdc4f31cfecc418b3";
    private final double[] values;
    public EmbeddingVector(String model, List<Double> input) {
        if (!MODEL.equals(model) || input == null || input.size()!=384) throw new KnowledgeFailure("EMBEDDING_UNAVAILABLE",503);
        values=new double[384]; double norm=0;
        for (int i=0;i<384;i++) {
            Double value=input.get(i);
            if(value==null || !Double.isFinite(value) || Math.abs(value)>1.001) throw new KnowledgeFailure("EMBEDDING_UNAVAILABLE",503);
            values[i]=value; norm+=value*value;
        }
        if(norm<0.99 || norm>1.01) throw new KnowledgeFailure("EMBEDDING_UNAVAILABLE",503);
    }
    public String sql() {
        var result=new StringBuilder("[");
        for(int i=0;i<384;i++) { if(i>0) result.append(','); result.append(values[i]); }
        return result.append(']').toString();
    }
    @Override public String toString() { return "EmbeddingVector[redacted]"; }
}
