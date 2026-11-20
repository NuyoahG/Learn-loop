package com.learnloop.material;

import java.util.*;
import java.nio.ByteBuffer;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import tools.jackson.databind.json.JsonMapper;

class ParserResultValidatorTest {
    private final ParserResultValidator validator=new ParserResultValidator();
    private final JsonMapper json=JsonMapper.builder().build();
    private Map<String,Object> sample(){
        return new HashMap<>(Map.of("schemaVersion",1,"attemptId","7:2","fileType","txt",
            "parserVersion","learnloop-parser/0.1.0","textCharCount",2,"warnings",List.of(),
            "blocks",List.of(Map.of("blockNo",1,"blockType","text","text","A😀","sourceLocator",
                Map.of("kind","text","lineStart",1,"lineEnd",1,"partIndex",1)))));
    }
    @Test void unicodeCountIsCodepoints(){assertEquals(2,validator.validate(json.writeValueAsString(sample()),"txt","7:2").characters());}
    @Test void pdfNoiseKeepsOriginalTextAndUsesCodepointOffsets() {
        var value=sample();value.put("fileType","pdf");
        value.put("blocks",List.of(Map.of("blockNo",1,"blockType","pdf_text","text","A😀",
                "sourceLocator",Map.of("kind","pdf","pageNumber",1,"partIndex",1),"noiseRanges",List.of(List.of(1,2)))));
        var parsed=validator.validate(json.writeValueAsString(value),"pdf","7:2");
        assertEquals("A😀",parsed.blocks().getFirst().text());
        assertEquals("[[1,2]]",parsed.blocks().getFirst().noiseJson());
    }
    @Test void noiseOutsideOriginalTextCannotPublish() {
        var value=sample();value.put("fileType","pdf");
        value.put("blocks",List.of(Map.of("blockNo",1,"blockType","pdf_text","text","A😀",
                "sourceLocator",Map.of("kind","pdf","pageNumber",1,"partIndex",1),"noiseRanges",List.of(List.of(1,3)))));
        assertThrows(MaterialException.class,()->validator.validate(json.writeValueAsString(value),"pdf","7:2"));
    }
    @Test void wrongAttemptCannotPublish(){assertThrows(MaterialException.class,()->validator.validate(json.writeValueAsString(sample()),"txt","7:3"));}
    @Test void countMismatchRejected(){var v=sample();v.put("textCharCount",3);assertThrows(MaterialException.class,()->validator.validate(json.writeValueAsString(v),"txt","7:2"));}
    @Test void unsupportedVersionRejected(){var v=sample();v.put("schemaVersion",2);assertThrows(MaterialException.class,()->validator.validate(json.writeValueAsString(v),"txt","7:2"));}
    @Test void untrustedSourceCannotEnterDatabase(){var v=sample();v.put("blocks",List.of(Map.of("blockNo",1,"blockType","text","text","AB","sourceLocator",Map.of("kind","pdf","pageNumber",1,"partIndex",1))));assertThrows(MaterialException.class,()->validator.validate(json.writeValueAsString(v),"txt","7:2"));}
    @Test void nonContinuousBlockNumbersRejected(){var v=sample();v.put("blocks",List.of(Map.of("blockNo",2,"blockType","text","text","AB","sourceLocator",Map.of("kind","text","lineStart",1,"lineEnd",1,"partIndex",1))));assertThrows(MaterialException.class,()->validator.validate(json.writeValueAsString(v),"txt","7:2"));}
    @Test void longPdfPreservesSourcePageNumbersAndWarnings() {
        var blocks = new ArrayList<Map<String,Object>>();
        for (int number = 1; number <= 51; number++) {
            blocks.add(Map.of("blockNo", number, "blockType", "pdf_text", "text", "字".repeat(4000),
                "sourceLocator", Map.of("kind", "pdf", "pageNumber", 150 + number, "partIndex", 1)));
        }
        var pagesWithoutText = java.util.stream.IntStream.rangeClosed(1, 101).boxed().toList();
        var response = sample();
        response.put("fileType", "pdf");
        response.put("blocks", blocks);
        response.put("textCharCount", 204000);
        response.put("warnings", List.of(Map.of("code", "PDF_PAGE_WITHOUT_TEXT",
            "message", "部分页面没有文字。", "pageNumbers", pagesWithoutText)));

        var parsed = validator.validate(json.writeValueAsString(response), "pdf", "7:2");
        assertEquals(204000, parsed.characters());
        assertEquals(201, parsed.blocks().getLast().source().get("pageNumber"));
        assertTrue(parsed.warningsJson().contains("101"));
    }
    @Test void boundedSubscriberCancelsBeforeAccumulatingOversizeBody(){
        var subscriber=new MaterialParserClient.LimitedBody();var subscription=mock(Flow.Subscription.class);
        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.allocate(MaterialParserClient.MAX_RESPONSE+1)));
        verify(subscription).cancel();assertTrue(subscriber.getBody().toCompletableFuture().isCompletedExceptionally());
    }
    @Test void oldAttemptsAndFinishedRowsFailFence(){
        MaterialRow row=new MaterialRow();row.setParseStatus("PARSING");row.setParseAttempt(3L);
        assertTrue(MaterialParsingService.current(row,3));assertFalse(MaterialParsingService.current(row,2));
        row.setParseStatus("PARSED");assertFalse(MaterialParsingService.current(row,3));
    }
}
