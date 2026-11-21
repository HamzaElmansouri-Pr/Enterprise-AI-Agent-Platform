package com.elmacore.agentplatform.knowledge.domain;

import java.util.ArrayList;
import java.util.List;

public final class TextChunking {
    public record Section(String text,Integer page) { @Override public String toString() { return "Section[redacted]"; } }
    public record Chunk(int ordinal,String text,Integer page) { @Override public String toString() { return "Chunk[redacted]"; } }
    public List<Chunk> split(List<Section> sections) {
        var chunks=new ArrayList<Chunk>(); int total=0;
        for(var section:sections) {
            String text=section.text().replace("\r\n","\n").replace('\r','\n');
            int[] cp=text.codePoints().toArray(); total+=cp.length;
            if(total>120000 || text.indexOf(0)>=0) throw new KnowledgeFailure("TEXT_LIMIT",400);
            for(int start=0;start<cp.length;) {
                int end=Math.min(start+800,cp.length);
                if(end<cp.length) {
                    for(int i=end;i>start+500;i--) {
                        if(cp[i-1]=='\n' || cp[i-1]=='.' || cp[i-1]=='!' || cp[i-1]=='?' || cp[i-1]==0x061F) { end=i; break; }
                    }
                }
                String value=new String(cp,start,end-start);
                if(!value.codePoints().allMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c))) {
                    if(chunks.size()>=256) throw new KnowledgeFailure("CHUNK_LIMIT",400);
                    chunks.add(new Chunk(chunks.size(),value,section.page()));
                }
                if(end==cp.length) break;
                start=Math.max(start+1,end-120);
            }
        }
        if(chunks.isEmpty()) throw new KnowledgeFailure("NO_EXTRACTABLE_TEXT",400);
        return List.copyOf(chunks);
    }
}
