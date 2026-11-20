package com.learnloop.journal;

import static com.learnloop.journal.JournalApi.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.learnloop.generation.GenerationClient;
import com.learnloop.generation.GenerationProperties;
import com.learnloop.learning.LearningMapper;
import com.learnloop.learning.LearningRows;
import com.learnloop.material.MaterialParsingService;
import com.learnloop.material.MaterialService;
import com.learnloop.modelsettings.ModelCredentialService;
import org.junit.jupiter.api.Test;

class JournalServiceTest {
    @Test
    void unicodeAnchorCannotSplitAnEmoji() {
        String source = "甲😀乙";
        assertTrue(JournalService.matchesSelection(source, new Anchor(1, 1, 3, 1, 3, "😀")));
        assertFalse(JournalService.matchesSelection(source, new Anchor(1, 1, 3, 1, 2, source.substring(1, 2))));
        assertFalse(JournalService.matchesSelection(source, new Anchor(1, 1, 3, 2, 3, source.substring(2, 3))));
        assertFalse(JournalService.matchesSelection(source, new Anchor(1, 1, 3, 1, 3, "甲乙")));
    }

    @Test
    void askingDoesNotPersistAndReceiptRejectsAnEditedAnswer() {
        var journal = mock(JournalMapper.class);
        var learning = mock(LearningMapper.class);
        var model = mock(GenerationClient.class);
        var creds = mock(ModelCredentialService.class);
        var properties = new GenerationProperties();
        properties.setEnabled(true);
        var service = new JournalService(journal, learning, mock(MaterialService.class),
                mock(MaterialParsingService.class), model, properties, creds);
        var anchor = new Anchor(1, 2, 3, 0, 2, "事务");
        when(learning.findMaterialSource(4, 1, 3)).thenReturn(new LearningRows.Source(1, 3, "", 2, "资料", "事务提交", "{}"));
        when(learning.lockUser(4)).thenReturn(4L);
        when(creds.requireKey(4)).thenReturn("synthetic-test-key");
        when(model.explain("事务提交", "事务", "", "BRIEF", "synthetic-test-key")).thenReturn("原始回答");

        var draft = service.ask(4, 1, new Ask(anchor, null, "BRIEF"));
        assertEquals("原始回答", draft.answer());
        verifyNoInteractions(journal);
        var tampered = new Draft(anchor, draft.question(), draft.detail(), "改写回答", draft.expiresAt(), draft.receipt());
        assertEquals("READING_DRAFT_EXPIRED", assertThrows(JournalException.class,
                () -> service.saveHighlight(4, 1, new SaveHighlight(tampered, "MINT"))).code);
        verify(journal, never()).saveHighlight(anyLong(), any(), any(), any());
    }
}
