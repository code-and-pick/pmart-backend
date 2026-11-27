package com.pmart.assistant.application;

import com.pmart.assistant.domain.RetrievedChunk;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * 검색 결과 → 답변. 기준선(발췌)과 생성(ChatModel) 두 구현. 장애는 {@link DependencyFailure} 로 던진다(→ 503, 완료 결과 없음).
 */
public interface AnswerGenerator {

  enum Mode {
    GENERATIVE,
    BASELINE_EXTRACTIVE,
    NONE
  }

  /**
   * @param grounded false 면 NO_GROUNDING(근거를 찾지 못함 — 정상 결과)
   * @param used 인용할 청크(서버 메타데이터로 인용을 만든다)
   */
  record Generated(
      boolean grounded,
      Mode mode,
      String text,
      List<RetrievedChunk> used,
      @Nullable String chatModel,
      Exit exit,
      List<String> attempts,
      @Nullable Double topSimilarity) {

    /** 종료 이유를 따로 적지 않는 생성기(발췌 기준선 등) — 답 방식에서 정한다. */
    public Generated(
        boolean grounded,
        Mode mode,
        String text,
        List<RetrievedChunk> used,
        @Nullable String chatModel) {
      this(grounded, mode, text, used, chatModel, Exit.of(mode), List.of(), null);
    }
  }

  /**
   * 문서 경로가 어디서 끝났는지(2026-11-27 ES-First 계획 P1 ① — 종료 지점 기록, 질문 원문 없이 지표로만).
   *
   * <ul>
   *   <li>BELOW_THRESHOLD: 임계값을 넘은 근거가 없음 — 모델을 부르지 않음
   *   <li>MODEL_UNGROUNDED: 모델이 grounded=false
   *   <li>GENERATED_FIRST·GENERATED_RETRY: 첫 시도·다시 생성에서 검사 통과
   *   <li>EXTRACTIVE_FALLBACK: 두 번 검사 실패 → 발췌
   *   <li>BELOW_FALLBACK_FLOOR: 두 번 검사 실패 + 1위 근거가 발췌 하한 아래 → 근거 없음
   *   <li>EXTRACTIVE_BASELINE·NO_GROUNDING: 생성 없는 기준선(발췌기), 또는 발췌로 바꿨는데 발췌할 것이 없음
   * </ul>
   *
   * <p>attempts = 생성 시도마다 PASS·JSON_INVALID·UNGROUNDED·인용 검사 위반 이름(쉼표)·(고객) CUSTOMER_&lt;검사&gt;.
   * topSimilarity = 1위 근거 혼합 유사도(근거가 없으면 null).
   */
  enum Exit {
    BELOW_THRESHOLD,
    MODEL_UNGROUNDED,
    GENERATED_FIRST,
    GENERATED_RETRY,
    EXTRACTIVE_FALLBACK,
    BELOW_FALLBACK_FLOOR,
    EXTRACTIVE_BASELINE,
    NO_GROUNDING;

    static Exit of(Mode mode) {
      return switch (mode) {
        case GENERATIVE -> GENERATED_FIRST;
        case BASELINE_EXTRACTIVE -> EXTRACTIVE_BASELINE;
        case NONE -> NO_GROUNDING;
      };
    }
  }

  Generated generate(String question, List<RetrievedChunk> ranked, List<RetrievedChunk> companions);

  /** 같은 채팅 세션의 앞 대화 한 턴(ADR-0084). 근거가 아니라 질문의 뜻을 이해하는 참고. */
  record PriorTurn(String question, String answer) {}

  /** 앞 대화를 함께 받는 생성(ADR-0084). 기본은 앞 대화를 쓰지 않는다(발췌 기준선 — 답은 근거 원문이라 앞 대화가 바꿀 것이 없다). */
  default Generated generate(
      String question,
      List<RetrievedChunk> ranked,
      List<RetrievedChunk> companions,
      List<PriorTurn> history) {
    return generate(question, ranked, companions);
  }

  /** 외부 의존(모델) 장애. */
  class DependencyFailure extends RuntimeException {
    public DependencyFailure(String message, @Nullable Throwable cause) {
      super(message, cause);
    }
  }

  String NO_GROUNDING_TEXT = "허용된 문서에서 이 질문을 뒷받침할 근거를 찾지 못했습니다. 확인할 수 없습니다.";
}
