package com.learnloop.tutor;

import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TutorRetrievalServiceTest {
    @Test void capabilityPinsOwnerScopeAndExpiresWithoutTrustingModelIdentity() {
        var mapper=mock(TutorRetrievalMapper.class);
        var summaries=mock(LearningSummaryMapper.class);
        var clock=mock(Clock.class); var now=Instant.parse("2026-11-17T00:00:00Z");
        when(clock.instant()).thenReturn(now);
        var service=new TutorRetrievalService(mapper,summaries,clock);
        var token=service.open(17,23L,List.of());
        when(mapper.sources(17,23L,"事务")).thenReturn(List.of(new TutorRetrievalMapper.Source("SOURCE:8",23L,"数据库","{}","事务原文")));
        var result=service.retrieve(token,new TutorRetrievalService.Query("search_material","事务",""));
        assertThat(result.items()).hasSize(1);
        verify(mapper).sources(17,23L,"事务");
        assertThat(service.cited(token,List.of("SOURCE:8"))).hasSize(1);
        assertThatThrownBy(() -> service.cited(token,List.of("SOURCE:other"))).isInstanceOf(ResponseStatusException.class);
        when(clock.instant()).thenReturn(now.plusSeconds(66));
        assertThat(service.authorized(token)).isFalse();
        assertThatThrownBy(() -> service.retrieve(token,new TutorRetrievalService.Query("search_material","事务","")))
            .isInstanceOf(ResponseStatusException.class);
    }
    @Test void rejectsUnownedAttemptRepeatedToolExcessBudgetAndRevokedToken() {
        var mapper=mock(TutorRetrievalMapper.class);
        var summaries=mock(LearningSummaryMapper.class);
        var service=new TutorRetrievalService(mapper,summaries);
        var token=service.open(1,null,List.of());
        assertThatThrownBy(() -> service.retrieve(token,new TutorRetrievalService.Query("attempt_detail","","PRACTICE:other")))
            .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(summaries);
        var query=new TutorRetrievalService.Query("search_notes","事务","");
        service.retrieve(token,query);
        assertThatThrownBy(() -> service.retrieve(token,query)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.retrieve(token,new TutorRetrievalService.Query("search_notes","隔离", "")))
            .isInstanceOf(ResponseStatusException.class);
        service.close(token);
        assertThat(service.authorized(token)).isFalse();
        assertThat(service.authorized(null)).isFalse();
    }
}
