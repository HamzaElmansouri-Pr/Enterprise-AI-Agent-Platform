package com.elmacore.agentplatform.knowledge.infrastructure;

import com.elmacore.agentplatform.knowledge.application.DocumentExtraction;
import com.elmacore.agentplatform.knowledge.domain.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import java.io.*;
import java.util.*;

public final class PdfBoxExtraction implements DocumentExtraction {
    @Override public List<TextChunking.Section> extract(String contentType,byte[] source) {
        if(contentType.equals("text/plain")) return List.of(new TextChunking.Section(Upload.utf8(source),null));
        try(var pdf=Loader.loadPDF(source)) {
            if(pdf.isEncrypted()) throw new KnowledgeFailure("ENCRYPTED_PDF",400);
            if(pdf.getNumberOfPages()>25) throw new KnowledgeFailure("PAGE_LIMIT",400);
            var sections=new ArrayList<TextChunking.Section>();int length=0;
            var stripper=new PDFTextStripper();stripper.setSortByPosition(true);
            for(int page=1;page<=pdf.getNumberOfPages();page++) {
                stripper.setStartPage(page);stripper.setEndPage(page);
                var writer=new BoundedTextWriter(2*(120000-length));stripper.writeText(pdf,writer);
                String text=writer.toString();length+=text.codePointCount(0,text.length());
                if(length>120000 || text.indexOf(0)>=0) throw new KnowledgeFailure("TEXT_LIMIT",400);
                sections.add(new TextChunking.Section(text,page));
            }
            return List.copyOf(sections);
        } catch(InvalidPasswordException error) { throw new KnowledgeFailure("ENCRYPTED_PDF",400); }
        catch(TextOverflow error) { throw new KnowledgeFailure("TEXT_LIMIT",400); }
        catch(IOException|IllegalArgumentException error) { throw new KnowledgeFailure("INVALID_PDF",400); }
    }
    private static final class TextOverflow extends IOException {}
    private static final class BoundedTextWriter extends Writer {
        private final int limit;private final StringBuilder value=new StringBuilder();
        BoundedTextWriter(int limit) {this.limit=limit;}
        @Override public void write(char[] chars,int offset,int count)throws IOException {
            if((long)value.length()+count>limit) throw new TextOverflow();value.append(chars,offset,count);
        }
        @Override public void flush(){} @Override public void close(){}
        @Override public String toString(){return value.toString();}
    }
}
