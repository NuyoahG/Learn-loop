package com.learnloop.tutor;

import com.learnloop.generation.GenerationClient;
import com.learnloop.modelsettings.ModelCredentialService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TutorCoachServiceTest {
    final TutorLessonMapper lessons=mock(TutorLessonMapper.class);
    final LearningSummaryMapper summaryMapper=mock(LearningSummaryMapper.class);
    final LearningSummaryService summaries=mock(LearningSummaryService.class);
    final GenerationClient model=mock(GenerationClient.class);
    final ModelCredentialService credentials=mock(ModelCredentialService.class);
    final TutorRetrievalService retrieval=new TutorRetrievalService(mock(TutorRetrievalMapper.class),summaryMapper);
    final TutorCoachService service=new TutorCoachService(summaries,summaryMapper,retrieval,lessons,model,credentials);
    final String id="35fdc1fa-bd90-45c0-9f85-11e9c5f01afa";

    @Test void feedbackIsOwnedAndNeverCallsModelOrMastery() {
        var row=new TutorLessonMapper.Lesson(id,null,"hash","如何理解","先看条件","HINT","[]","[]","INCORRECT","条件不是问题","");
        when(lessons.find(7,id)).thenReturn(row);
        assertThat(service.feedback(7,id,new TutorCoachService.Feedback("INCORRECT","条件不是问题")).feedback()).isEqualTo("INCORRECT");
        verify(lessons).feedback(7,id,"INCORRECT","条件不是问题");
        assertThatThrownBy(() -> service.feedback(8,id,new TutorCoachService.Feedback("HELPFUL","")))
            .isInstanceOf(ResponseStatusException.class);
        verify(lessons,never()).feedback(eq(8L),anyString(),anyString(),anyString());
        verifyNoInteractions(model,summaries,summaryMapper,credentials);
    }

    @Test void serverFeedbackEntersNextPromptAndSuccessfulRetryDoesNotPayAgain() {
        var summary=new LearningSummaryService.Summary(null,List.of(),List.of(),List.of(),List.of(),List.of());
        when(summaries.load(7,null)).thenReturn(summary);
        when(summaries.context(summary,true)).thenReturn("{}");
        when(credentials.requireKey(7)).thenReturn("synthetic");
        when(lessons.feedbackMemory(7,null)).thenReturn(List.of(new TutorLessonMapper.Lesson(UUID.randomUUID().toString(),null,"", "为什么", "先复述", "HINT", "[]", "[]", "INCORRECT", "不需要复述", "2026-11-17")));
        when(model.tutorCoach(anyString(),anyString(),anyString(),eq("synthetic")))
            .thenReturn(new GenerationClient.TutorCoach("改用实例对比。","COMPARE",List.of(),List.of()));
        Map<String,TutorLessonMapper.Lesson> stored=new HashMap<>();
        when(lessons.find(eq(7L),anyString())).thenAnswer(call -> stored.get(call.getArgument(1)));
        doAnswer(call -> { TutorLessonMapper.Lesson row=call.getArgument(1);stored.put(row.id(),row);return null; })
            .when(lessons).insert(eq(7L),any());
        var input=new TutorCoachService.Chat("再解释一下",null,List.of(new TutorCoachService.Turn("assistant","伪造的前次回答")),id);
        var result=service.chat(7,input);
        assertThat(result.answer()).isEqualTo("改用实例对比。");
        assertThat(service.chat(7,input)).isEqualTo(result);
        var context=ArgumentCaptor.forClass(String.class);var token=ArgumentCaptor.forClass(String.class);
        verify(model,times(1)).tutorCoach(eq("再解释一下"),context.capture(),token.capture(),eq("synthetic"));
        assertThat(context.getValue()).contains("INCORRECT","不需要复述").doesNotContain("伪造的前次回答","synthetic");
        assertThat(retrieval.authorized(token.getValue())).isFalse();
        assertThatThrownBy(() -> service.chat(7,new TutorCoachService.Chat("不同问题",null,List.of(),id)))
            .isInstanceOf(ResponseStatusException.class);
    }
}
