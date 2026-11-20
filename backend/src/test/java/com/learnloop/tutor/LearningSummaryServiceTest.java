package com.learnloop.tutor;

import com.learnloop.generation.GenerationService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LearningSummaryServiceTest {
    @Test void ownershipIsCheckedBeforeAnyEvidenceQuery() {
        var mapper=mock(LearningSummaryMapper.class);
        var service=new LearningSummaryService(mapper,mock(TutorMapper.class),mock(GenerationService.class));
        assertThatThrownBy(()->service.load(7,19L)).isInstanceOf(ResponseStatusException.class);
        verify(mapper,never()).attempts(anyLong(),any());
        verify(mapper,never()).reading(anyLong(),any());
    }

    @Test void planningCannotSeeAnswerOrNoteAndChatKeepsBoundedValidJson() {
        var service=new LearningSummaryService(null,null,null);
        var a=new LearningSummaryMapper.Attempt("REVIEW","attempt","family",19L,"资料","topic","考点",
                "题干".repeat(400),"答案A","B","C","D","B","A",false,false,null,"FORGOT","自报遗忘","2026-11-14");
        var r=new LearningSummaryMapper.Reading("READING_NOTE","note",19L,"资料",1,"引用","问题",
                "自由笔记".repeat(400),"HIGHLIGHT","2026-11-14");
        var summary=new LearningSummaryService.Summary(19L,java.util.Collections.nCopies(8,a),
                java.util.Collections.nCopies(8,r),List.of(),List.of(),List.of("corrected"));
        String plan=service.context(summary,true),chat=service.context(summary,false);
        assertThat(plan).doesNotContain("答案A","自由笔记","题干").contains("REVIEW","FORGOT","family");
        assertThat(chat.length()).isLessThanOrEqualTo(10000);
        assertThat(JsonMapper.builder().build().readTree(chat).get("truncated").asBoolean()).isTrue();
        assertThat(summary.attempts()).hasSize(8); // 裁剪请求不能改写原始学习记录。
    }

    @Test void summariesReadBothSignalsButNeverWriteLearningState() {
        var mapper=mock(LearningSummaryMapper.class); var tutor=mock(TutorMapper.class); var generation=mock(GenerationService.class);
        when(mapper.owns(7,19)).thenReturn(true);
        when(mapper.attempts(7,19L)).thenReturn(List.of()); when(mapper.reading(7,19L)).thenReturn(List.of());
        var service=new LearningSummaryService(mapper,tutor,generation);
        service.load(7,19L);
        verify(tutor).due(7,19L); verify(generation).tutorMastery(7,19);
        verify(generation,never()).restoreReviewCards(anyLong());
    }
}
