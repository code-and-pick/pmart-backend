package com.pmart.assistant.application;

import com.pmart.assistant.domain.ExtractiveComposer;
import com.pmart.assistant.domain.RetrievedChunk;
import java.util.List;

/** 기준선 답변: 상위 근거를 온전한 청크 단위로 보여 준다(생성 없음). */
public class ExtractiveAnswerGenerator implements AnswerGenerator {

  private final int maxChars;
  private final int maxChunks;

  public ExtractiveAnswerGenerator(int maxChars, int maxChunks) {
    this.maxChars = maxChars;
    this.maxChunks = maxChunks;
  }

  @Override
  public Generated generate(
      String question, List<RetrievedChunk> ranked, List<RetrievedChunk> companions) {
    if (ranked.isEmpty()) {
      return new Generated(
          false,
          Mode.NONE,
          NO_GROUNDING_TEXT,
          List.of(),
          null,
          Exit.BELOW_THRESHOLD,
          List.of(),
          null);
    }
    var c = ExtractiveComposer.compose(ranked, companions, maxChars, maxChunks);
    return new Generated(true, Mode.BASELINE_EXTRACTIVE, c.text(), c.used(), null);
  }
}
