package com.learnloop.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.learnloop.auth.LoginUser;
import com.learnloop.generation.GenerationClient;
import com.learnloop.generation.GenerationProperties;
import com.learnloop.learning.LearningMapper;
import com.learnloop.material.MaterialParsingService;
import com.learnloop.material.MaterialService;
import com.learnloop.modelsettings.ModelCredentialService;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

class JournalNotesPaginationTest {
    @Test
    void defaultsToTwentyAndSupportsTwelveWithLookahead() throws Exception {
        var journal = mock(JournalMapper.class);
        var service = new JournalService(journal, mock(LearningMapper.class), mock(MaterialService.class),
                mock(MaterialParsingService.class), mock(GenerationClient.class),
                new GenerationProperties(), mock(ModelCredentialService.class));
        var mvc = MockMvcBuilders.standaloneSetup(new JournalController(service))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
        var note = new JournalApi.Note("v", "笔记", "rev", null, "题目", 10, "资料", null, null);
        var user = new LoginUser(4L, "demo_learner", "synthetic");
        when(journal.notes(4L, "", 21, 0L)).thenReturn(Collections.nCopies(21, note));
        when(journal.notes(4L, "笔记", 13, 12L)).thenReturn(List.of(note));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null));
        try {
            var json = JsonMapper.builder().build();
            var defaults = json.readTree(mvc.perform(get("/api/notes"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
            assertEquals(20, defaults.path("records").size());
            assertEquals(true, defaults.path("hasMore").asBoolean());

            var second = json.readTree(mvc.perform(get("/api/notes")
                            .param("size", "12").param("page", "2").param("q", "笔记"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
            assertEquals(2, second.path("page").asInt());
            assertEquals(1, second.path("records").size());
            assertEquals(false, second.path("hasMore").asBoolean());
            mvc.perform(get("/api/notes").param("size", "21")).andExpect(status().isBadRequest());
            verify(journal).notes(4L, "", 21, 0L);
            verify(journal).notes(4L, "笔记", 13, 12L);
            verify(journal, times(2)).readingNotesReady();
            verifyNoMoreInteractions(journal);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
