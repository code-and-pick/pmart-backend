package com.pmart.storefront.api;

import com.pmart.storefront.api.generated.InternalApi;
import com.pmart.storefront.api.generated.model.ListPublishedThemesInternal200Response;
import com.pmart.storefront.api.generated.model.ListPublishedThemesInternal200ResponseThemesInner;
import com.pmart.storefront.application.LayoutService;
import com.pmart.storefront.domain.LayoutRules;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 InternalApi 의 구현 진입점. 아직 구현하지 않은 메서드는 인터페이스 default 가 501 을 돌려준다. 업무 규칙은 application/domain 에
 * 두고 여기서는 요청·응답 변환만 한다.
 */
@RestController
public class InternalController implements InternalApi {

  private final com.pmart.storefront.application.RankingSignals ranking;
  private final LayoutService layouts;

  public InternalController(
      com.pmart.storefront.application.RankingSignals ranking, LayoutService layouts) {
    this.ranking = ranking;
    this.layouts = layouts;
  }

  /** 순위 신호 모두 다시 내기(ADR-0072 — 새 검색 색인 채우기). */
  @Override
  public org.springframework.http.ResponseEntity<
          com.pmart.storefront.api.generated.model.RepublishRankingSignals200Response>
      republishRankingSignals() {
    return org.springframework.http.ResponseEntity.ok(
        new com.pmart.storefront.api.generated.model.RepublishRankingSignals200Response()
            .published(ranking.republishAll()));
  }

  /** 지금 게시된 진열의 테마(0.14.0 — promotion 행사 대상 THEME). 게시 버전이 없으면 0·빈 목록. */
  @Override
  public ResponseEntity<ListPublishedThemesInternal200Response> listPublishedThemesInternal() {
    List<LayoutRules.Theme> themes =
        layouts.currentBody().map(LayoutRules.Body::themes).orElse(List.of());
    List<ListPublishedThemesInternal200ResponseThemesInner> out = new ArrayList<>();
    for (LayoutRules.Theme t : themes) {
      out.add(
          new ListPublishedThemesInternal200ResponseThemesInner()
              .sectionId(t.sectionId())
              .kind(ListPublishedThemesInternal200ResponseThemesInner.KindEnum.fromValue(t.kind()))
              .title(t.title())
              .productIds(new ArrayList<>(t.productIds())));
    }
    return ResponseEntity.ok(
        new ListPublishedThemesInternal200Response()
            .layoutVersion(layouts.currentVersion())
            .themes(out));
  }
}
