package com.pmart.ragquery.api;

import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import com.pmart.ragquery.application.RagSearch;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/internal/rag-searches} — 질문 하나를 ES-First 로 검색(ADR-0099). 지금은 서비스 간 경로(게이트웨이에
 * 없음, 사용자 토큰 없음) — 화면이 쓰게 되면 공개 경로·권한(운영자·고객 범위)을 계약과 함께 정한다.
 */
@RestController
public class RagSearchController {

  /** 요청: 범위 OPERATOR·CUSTOMER, 질문. */
  public record SearchRequest(@Nullable String scope, @Nullable String question) {}

  private final RagSearch search;

  public RagSearchController(RagSearch search) {
    this.search = search;
  }

  @PostMapping("/api/v1/internal/rag-searches")
  public RagSearch.Result search(@RequestBody SearchRequest req) {
    String scope = req.scope() == null ? "" : req.scope().strip().toUpperCase();
    if (!("OPERATOR".equals(scope) || "CUSTOMER".equals(scope))
        || req.question() == null
        || req.question().isBlank()
        || req.question().length() > 500) {
      throw new PmartException(
          ErrorCode.VALIDATION_FAILED.name(),
          HttpStatus.BAD_REQUEST,
          "scope(OPERATOR·CUSTOMER)와 question(500자 이하)이 필요합니다.",
          false,
          List.of());
    }
    return search.search(scope, req.question().strip());
  }
}
