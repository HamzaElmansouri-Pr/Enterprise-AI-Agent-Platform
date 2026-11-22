package com.elmacore.agentplatform.knowledge;

import com.elmacore.agentplatform.knowledge.domain.*;
import com.elmacore.agentplatform.knowledge.infrastructure.PdfBoxExtraction;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.encryption.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KnowledgeDomainTest {
    @Test void strictUtf8AndDefensiveBytesKeepUntrustedTextLiteral() {
        String text="القبول\r\nBonjour les étudiants.\n${IGNORE_ME}\nhttps://127.0.0.1:9/never-fetch";
        byte[] bytes=text.getBytes(StandardCharsets.UTF_8);var source=new Upload(" admissions.txt ","text/plain",bytes);
        bytes[0]=0;assertThat(source.filename()).isEqualTo("admissions.txt");assertThat(Upload.utf8(source.bytes())).isEqualTo(text);
        byte[] copy=source.bytes();copy[0]=0;assertThat(Upload.utf8(source.bytes())).isEqualTo(text);
        assertThat(source.toString()).doesNotContain(text);assertThat(source.sha256()).hasSize(64);
        assertThrows(IllegalArgumentException.class,()->new Upload("bad.txt","text/plain",new byte[]{(byte)0xC3,0x28}));
        assertThrows(IllegalArgumentException.class,()->new Upload("bad.txt","text/plain",new byte[]{0}));
    }
    @ParameterizedTest @ValueSource(strings={"../a.txt","a\\b.txt","bad\nname.txt","bad\u202ename.txt","a.zip",".",".."})
    void unsafeFilenamesAreRejected(String name) {
        assertThrows(IllegalArgumentException.class,()->new Upload(name,"text/plain","text".getBytes()));
    }
    @Test void uploadByteAndTextLimitsAreCheckedBeforeInference() {
        assertThrows(IllegalArgumentException.class,()->new Upload("a.txt","text/plain",new byte[2097153]));
        assertThrows(IllegalArgumentException.class,()->new Upload("a.txt","text/plain","a".repeat(120001).getBytes()));
        assertThrows(IllegalArgumentException.class,()->new Upload("a.pdf","application/pdf","fake".getBytes()));
        assertThrows(IllegalArgumentException.class,()->new Upload("a.txt","application/octet-stream","text".getBytes()));
    }
    @Test void chunkingIsDeterministicUnicodeSafeAndPreservesOverlapAndParagraphs() {
        String text=("العربية 🎓 français.\r\n\r\n").repeat(300);
        var input=List.of(new TextChunking.Section(text,2));var chunker=new TextChunking();
        var chunks=chunker.split(input);assertThat(chunks).isEqualTo(chunker.split(input)).hasSizeGreaterThan(1);
        for(int i=0;i<chunks.size();i++) {
            var c=chunks.get(i);assertThat(c.ordinal()).isEqualTo(i);assertThat(c.page()).isEqualTo(2);
            assertThat(c.text()).doesNotContain("\r");assertThat(c.text().codePointCount(0,c.text().length())).isBetween(1,1200);
            assertThat(Character.isLowSurrogate(c.text().charAt(0))).isFalse();
            assertThat(Character.isHighSurrogate(c.text().charAt(c.text().length()-1))).isFalse();
            if(i>0) {
                String previous=chunks.get(i-1).text();String overlap=previous.substring(previous.offsetByCodePoints(previous.length(),-120));
                assertThat(c.text()).startsWith(overlap);
            }
        }
    }
    @Test void emptyHugeAndOverChunkedDocumentsFailWithSafeCodes() {
        var chunker=new TextChunking();
        assertThat(assertThrows(KnowledgeFailure.class,()->chunker.split(List.of(new TextChunking.Section(" \n\u00a0",null)))).code()).isEqualTo("NO_EXTRACTABLE_TEXT");
        assertThat(assertThrows(KnowledgeFailure.class,()->chunker.split(List.of(new TextChunking.Section("x".repeat(120001),null)))).code()).isEqualTo("TEXT_LIMIT");
        var sections=new ArrayList<TextChunking.Section>();for(int i=0;i<257;i++)sections.add(new TextChunking.Section("x",null));
        assertThat(assertThrows(KnowledgeFailure.class,()->chunker.split(sections)).code()).isEqualTo("CHUNK_LIMIT");
        assertThat(chunker.split(List.of(new TextChunking.Section("x".repeat(120000),null)))).hasSizeLessThanOrEqualTo(256);
    }
    @Test void vectorDimensionsModelFiniteAndNormAreEnforced() {
        var vector=new ArrayList<Double>(Collections.nCopies(384,0.0));vector.set(0,1.0);
        assertThat(new EmbeddingVector(EmbeddingVector.MODEL,vector).sql()).startsWith("[1.0,0.0");
        assertThrows(KnowledgeFailure.class,()->new EmbeddingVector("different-model",vector));
        assertThrows(KnowledgeFailure.class,()->new EmbeddingVector(EmbeddingVector.MODEL,List.of(1.0,0.0)));
        assertThrows(KnowledgeFailure.class,()->new EmbeddingVector(EmbeddingVector.MODEL,Collections.nCopies(384,0.0)));
        for(double invalid:List.of(Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,2.0)) {
            vector.set(0,invalid);assertThrows(KnowledgeFailure.class,()->new EmbeddingVector(EmbeddingVector.MODEL,vector));
        }
    }
    @Test void onlyFailedDocumentsAreRetryable() {
        DocumentStatus.FAILED.requireRetryable();
        for(var state:List.of(DocumentStatus.PENDING,DocumentStatus.PROCESSING,DocumentStatus.READY)) {
            var failure=assertThrows(KnowledgeFailure.class,state::requireRetryable);
            assertThat(failure.status()).isEqualTo(409);
        }
    }
    @Test void invalidPersistedDocumentStateFailsClosed() {
        var now=java.time.Instant.now();var id=java.util.UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,()->new KnowledgeDocument(id,id,"file.txt","text/plain",1,"0".repeat(64),DocumentStatus.READY,EmbeddingVector.MODEL,0,null,now,now,List.of(),false));
        assertThrows(IllegalArgumentException.class,()->new KnowledgeDocument(id,id,"file.txt","text/plain",1,"0".repeat(64),DocumentStatus.FAILED,EmbeddingVector.MODEL,0,null,now,now,List.of(),false));
        assertThrows(IllegalArgumentException.class,()->new KnowledgeDocument(id,id,"file.txt","text/plain",1,"0".repeat(64),DocumentStatus.PENDING,"wrong-model",0,null,now,now,List.of(),false));
    }
    static byte[] pdf(int pages,boolean encrypted,String text)throws Exception {
        try(var document=new PDDocument();var output=new ByteArrayOutputStream()) {
            for(int i=0;i<pages;i++) {
                var page=new PDPage();document.addPage(page);
                if(!text.isEmpty())try(var stream=new PDPageContentStream(document,page)) {
                    stream.beginText();stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);
                    stream.newLineAtOffset(30,750);stream.showText(text);stream.endText();
                }
            }
            if(encrypted)document.protect(new StandardProtectionPolicy("test-owner","test-user",new AccessPermission()));
            document.save(output);return output.toByteArray();
        }
    }
    @Test void realPdfBoxExtractsSelectableTextWithReliablePages()throws Exception {
        var sections=new PdfBoxExtraction().extract("application/pdf",pdf(2,false,"Admissions passport requirements"));
        assertThat(sections).hasSize(2);assertThat(sections.getFirst().page()).isEqualTo(1);
        assertThat(sections.getLast().page()).isEqualTo(2);assertThat(sections.getFirst().text()).contains("Admissions passport requirements");
    }
    @Test void encryptedMalformedExcessivePagesAndImageOnlyPdfFailSafely()throws Exception {
        var extractor=new PdfBoxExtraction();
        assertThat(assertThrows(KnowledgeFailure.class,()->extractor.extract("application/pdf",pdf(1,true,"private"))).code()).isEqualTo("ENCRYPTED_PDF");
        assertThat(assertThrows(KnowledgeFailure.class,()->extractor.extract("application/pdf",pdf(26,false,""))).code()).isEqualTo("PAGE_LIMIT");
        assertThat(assertThrows(KnowledgeFailure.class,()->extractor.extract("application/pdf","%PDF-broken".getBytes())).code()).isEqualTo("INVALID_PDF");
        var empty=extractor.extract("application/pdf",pdf(1,false,""));
        assertThat(assertThrows(KnowledgeFailure.class,()->new TextChunking().split(empty)).code()).isEqualTo("NO_EXTRACTABLE_TEXT");
    }
}
