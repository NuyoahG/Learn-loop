package com.learnloop.learning;

import com.learnloop.generation.GenerationClient;
import com.learnloop.generation.GenerationException;
import com.learnloop.generation.GenerationProperties;
import com.learnloop.modelsettings.ModelCredentialService;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnswerExplanationServiceTest {
    static final String ID="11111111-1111-4111-8111-111111111111";
    final AnswerExplanationMapper mapper=mock(AnswerExplanationMapper.class);
    final GenerationClient model=mock(GenerationClient.class);
    final ModelCredentialService credentials=mock(ModelCredentialService.class);
    final GenerationProperties generation=new GenerationProperties();
    final LearningProperties learning=new LearningProperties();
    final AnswerExplanationService service=new AnswerExplanationService(mapper,model,credentials,generation,learning);
    AnswerExplanationServiceTest(){learning.setEnabled(true);generation.setEnabled(true);}
    static AnswerExplanationMapper.Answer row(String explanation) {
        return new AnswerExplanationMapper.Answer("提交后应如何保存修改？","持久保存","随机丢弃","撤销修改","只留内存",
                "A",explanation,"持久性","已提交结果应持久保存。","事务提交之后，所有修改应当持久保存。","B",null);
    }

    @Test void briefWorksForWrongOrRightAnswersWithoutAnyModelCallOrKey() {
        when(mapper.practice(7,ID)).thenReturn(row("  原文明确说明提交后应持久保存。  "));
        generation.setEnabled(false);
        assertThat(service.brief(7,"PRACTICE",ID).brief()).isEqualTo("原文明确说明提交后应持久保存。");
        when(mapper.practice(7,ID)).thenReturn(row(null));
        assertThat(service.brief(7,"PRACTICE",ID).brief()).contains("A","持久保存","已提交结果");
        verifyNoInteractions(model,credentials);
    }
    @Test void invalidOrUnsubmittedAttemptsCannotReachModelOrRevealAnswers() {
        assertThatThrownBy(()->service.brief(7,"PRACTICE","not-an-id")).isInstanceOf(LearningException.class);
        assertThatThrownBy(()->service.detailed(7,"OTHER",ID)).isInstanceOf(LearningException.class);
        verifyNoInteractions(mapper,model,credentials);
        assertThatThrownBy(()->service.brief(8,"PRACTICE",ID)).isInstanceOf(LearningException.class)
                .satisfies(ex->assertThat(((LearningException)ex).status()).isEqualTo(404));
        assertThatThrownBy(()->service.detailed(8,"PRACTICE",ID)).isInstanceOf(LearningException.class);
        verifyNoInteractions(model,credentials);
    }
    @Test void detailsUseOwnedFrozenQuestionSourceAndOnlyExplicitRequestCallsModel() {
        when(mapper.review(7,ID)).thenReturn(row(null));
        when(credentials.requireKey(7)).thenReturn("synthetic");
        when(model.explain(anyString(),anyString(),anyString(),eq("DETAILED"),eq("synthetic")))
                .thenAnswer(call->{
                    String source=call.getArgument(0),quote=call.getArgument(1);
                    assertThat(source).contains("资料原文（节选）","所有修改应当持久保存","正确选项：A","所选选项：B");
                    assertThat(source).contains(quote);
                    return "A 成立，因为提交后应持久保存；B 把持久性与随机丢失混淆。";
                });
        assertThat(service.detailed(7,"REVIEW",ID).answer()).contains("A 成立");
        verify(model,times(1)).explain(anyString(),anyString(),anyString(),eq("DETAILED"),eq("synthetic"));
        verify(mapper,never()).practice(anyLong(),anyString());
    }
    @Test void sourceBudgetPreservesUnicodeAndRequiredCorrectAnswerMarker() {
        var longRow=new AnswerExplanationMapper.Answer("长题干😀".repeat(1000),"甲😀".repeat(1000),"乙😀".repeat(1000),
                "丙😀".repeat(1000),"丁😀".repeat(1000),"C",null,"概念😀".repeat(1000),"定义😀".repeat(1000),"原文😀".repeat(3000),"A",null);
        var source=AnswerExplanationService.source(longRow);
        assertThat(source.codePointCount(0,source.length())).isLessThanOrEqualTo(4000);
        assertThat(source).contains("正确选项：C","所选选项：A","A.","B.","C.","D.","资料原文（节选）");
        assertThat(source.codePoints().anyMatch(cp->cp>=0xD800 && cp<=0xDFFF)).isFalse();
    }
    @Test void rejectsConcurrentPaidCallsAndReleasesGuardAfterProviderFailure() throws Exception {
        when(mapper.practice(7,ID)).thenReturn(row(null));when(credentials.requireKey(7)).thenReturn("synthetic");
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(model.explain(anyString(),anyString(),anyString(),anyString(),anyString())).thenAnswer(call->{
            entered.countDown();assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();
            throw new GenerationException(503,"MODEL_TIMEOUT","test provider timeout");
        });
        try(var pool=Executors.newSingleThreadExecutor()) {
            var first=pool.submit(()->service.detailed(7,"PRACTICE",ID));
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            try {
                assertThatThrownBy(()->service.detailed(7,"PRACTICE",ID)).isInstanceOf(LearningException.class)
                    .satisfies(ex->assertThat(((LearningException)ex).code()).isEqualTo("EXPLANATION_BUSY"));
            } finally {release.countDown();}
            assertThatThrownBy(()->first.get(5,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
        }
        doReturn("可以再次生成。").when(model).explain(anyString(),anyString(),anyString(),anyString(),anyString());
        assertThat(service.detailed(7,"PRACTICE",ID).answer()).isEqualTo("可以再次生成。");
    }
    @Test void sourceExcerptRetainsPublishedEvidenceNearEndOfLongBlock() {
        String quote="隔离性用于避免并发事务之间的干扰。";
        var row=new AnswerExplanationMapper.Answer("隔离性的意义？","避免并发干扰","随机丢弃","撤销修改","只留内存",
            "A",null,"隔离性","避免干扰","背景😀".repeat(1000)+quote,"B",quote);
        assertThat(AnswerExplanationService.source(row)).contains(quote,"正确选项：A","所选选项：B");
    }
}
