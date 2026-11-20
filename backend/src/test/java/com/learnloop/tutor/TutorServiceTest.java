package com.learnloop.tutor;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.learnloop.tutor.TutorMapper.*;

class TutorServiceTest {
    private static final String ID = "35fdc1fa-bd90-45c0-9f85-11e9c5f01afa";

    @Test
    void customFeedbackRequiresOwnedWrongAttemptAndPreservesUserText() {
        var mapper = mock(TutorMapper.class);
        var service = new TutorService(mapper);
        var feedback = new Feedback("OTHER", "  把邻域当成了整幅图  ");
        assertThatThrownBy(() -> service.save(17, "PRACTICE", ID, feedback))
                .isInstanceOf(ResponseStatusException.class);
        verify(mapper, never()).saveFeedback(anyLong(), anyString(), anyString(), any());
        when(mapper.wrongPractice(17, ID)).thenReturn(ID);
        var stored = service.save(17, "PRACTICE", ID, feedback);
        assertThat(stored.detail()).isEqualTo("把邻域当成了整幅图");
        verify(mapper).saveFeedback(17, "PRACTICE", ID, stored);
    }

    @Test
    void aSecondIndependentFamilyIsRequiredAndCorrectedSuggestionsStaySuppressed() {
        var mapper = mock(TutorMapper.class);
        var service = new TutorService(mapper);
        var first = error("one", "family1", "MISREAD");
        var duplicate = error("two", "family1", "MISREAD");
        var disputed = error("three", "family2", "QUESTION_ISSUE");
        when(mapper.recentErrors(17)).thenReturn(List.of(first, duplicate, disputed));
        assertThat(service.suggestion(17)).isNull();
        when(mapper.recentErrors(17)).thenReturn(List.of(first, duplicate, error("four", "family3", "MISREAD")));
        assertThat(service.suggestion(17).evidence()).extracting(TutorService.Evidence::id).containsExactly("one", "four");
        var suppressed = TutorService.candidates(mapper.recentErrors(17)).stream()
                .map(TutorService.Suggestion::key).toList();
        when(mapper.suppressed(17)).thenReturn(suppressed);
        assertThat(service.suggestion(17)).isNull();
    }

    @Test
    void onlyExplicitConfusionAndSameTopicWrongAnswerProduceReadingSuggestion() {
        var wrong = error("attempt", "family", "MISREAD");
        var passive = new Mark("mark", 2, "图像处理", 1, "原文", "", "HIGHLIGHT", "topic", "邻域统计");
        var unrelated = new Mark("mark", 2, "图像处理", 1, "原文", "", "CONFUSED", "other", "其他考点");
        var confirmed = new Mark("mark", 2, "图像处理", 1, "原文", "", "CONFUSED", "topic", "邻域统计");
        assertThat(TutorService.readingSuggestions(List.of(wrong), List.of(passive, unrelated))).isEmpty();
        assertThat(TutorService.readingSuggestions(List.of(), List.of(confirmed))).isEmpty();
        assertThat(TutorService.readingSuggestions(List.of(wrong), List.of(confirmed)).getFirst().evidence())
                .extracting(TutorService.Evidence::kind).containsExactly("MARK", "ATTEMPT");
    }

    private static ErrorEvidence error(String id, String family, String reason) {
        return new ErrorEvidence(id, family, "topic", "邻域统计", "直方图", "均衡化", 2, "图像处理", reason, "");
    }
}
