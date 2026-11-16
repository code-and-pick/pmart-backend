package com.pmart.assistant.infrastructure.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pmart.assistant.application.KnowledgeJobs;
import com.pmart.assistant.infrastructure.SnapshotStore;
import com.pmart.assistant.infrastructure.SnapshotStore.ChunkRow;
import com.pmart.assistant.infrastructure.embedding.LocalHashEmbeddingModel;
import com.pmart.assistant.infrastructure.ingest.KnowledgeManifest.Doc;
import com.pmart.assistant.infrastructure.ingest.KnowledgeManifest.Kind;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 실제 저장소 문서(manifest 전체)로 청크·지위·생애 판정 회귀를 고정한다. DB 없이 — 임베딩 캐시만 모의.
 *
 * <p>검토 1 회귀: 제안 ADR 이 승인 결정을 SUPERSEDED 로 만들지 않는다(0025→0019, 0015→Commerce), 승인 ADR 은 명시 결정만
 * 대체(0026→0011).
 */
class RealDocumentsIngestTest {

  static final Path REPO = Path.of("../..").toAbsolutePath().normalize();
  static KnowledgeManifest manifest;
  static List<ChunkRow> rows;

  @BeforeAll
  static void build() throws IOException {
    manifest = KnowledgeJobs.loadManifest();
    SnapshotStore store = mock(SnapshotStore.class);
    when(store.cachedEmbedding(any(), any())).thenReturn(Optional.empty());
    KnowledgeIngestor ingestor =
        new KnowledgeIngestor(
            null, store, new LocalHashEmbeddingModel(384), null, Clock.systemUTC(), 1500);
    Map<String, byte[]> bytes = new LinkedHashMap<>();
    for (Doc d : manifest.documents()) {
      bytes.put(d.path(), Files.readAllBytes(REPO.resolve(d.path())));
    }
    rows = ingestor.buildChunks(manifest, bytes, new LocalHashEmbeddingModel(384).spec());
  }

  /**
   * 운영자 RAG 에서 뺀 ADR(2026-11-15 사용자 "그건 일반 운영자쪽에서 안봐도되는내용인데") — 서비스 경계·인프라·인증 구현·배포·관측·RAG 설계. 업무
   * 직원이 고객 응대·업무 판단에 쓰는 결정만 manifest 에 둔다. 새 ADR 은 manifest 에 넣거나 여기에 더해야 한다(안 정하면 실패).
   */
  private static final Set<String> EXCLUDED_ADRS =
      Set.of(
          "0001", "0002", "0003", "0004", "0005", "0006", "0007", "0008", "0009",
          "0010", // 서비스 경계·사가 방식·사건 봉투·시각 저장
          "0011", "0012", "0013", "0016", "0017", "0018", "0019", "0020", "0021",
          "0022", // 인증·이미지·배포·저장소·DB·결제 구현·검색·비회원 세션
          "0023", "0024", "0025", "0026", "0027", "0028", "0029", "0030", "0031",
          "0032", // 주소 API·프로필·로그인·서비스 간 호출·설정·컨테이너·관측·검색 ES·Eureka
          "0033", "0034", "0035", "0036", "0037", "0038", "0039", "0050", "0051",
          "0053", // 설정 갱신·Kafka·소비·RAG·승인 회신·OR-Tools·Native
          "0054", "0055", "0056", "0057", "0058", "0059",
          "0061"); // 인증 분리·Google·직원 로그인(업무 흐름은 flows 문서)·HTTPS·배차 서버·CSRF·로그아웃 전파

  // 0062 개인정보 원문 열람·0063 리뷰 1차·0064 알림 1차는 2026-11-16 manifest 에 넣음(사용자 "셋 다 넣고", RAG 영역 PC 4).

  @Test
  void manifestListsExistingFilesExplicitly() {
    assertThat(manifest.documents())
        .allSatisfy(d -> assertThat(REPO.resolve(d.path())).isRegularFile());
    Set<String> adrFiles;
    try (var s = Files.list(REPO.resolve("docs/adr"))) {
      adrFiles =
          s.map(p -> p.getFileName().toString())
              .filter(n -> n.matches("^\\d{4}-.*\\.md$"))
              .map(n -> n.substring(0, 4))
              .collect(Collectors.toSet());
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    Set<String> listed =
        manifest.documents().stream()
            .filter(d -> d.kind() == Kind.ADR)
            .map(d -> d.path().substring("docs/adr/".length(), "docs/adr/".length() + 4))
            .collect(Collectors.toSet());
    assertThat(listed)
        .as("manifest 의 ADR 은 뺀 목록과 겹치지 않는다")
        .doesNotContainAnyElementsOf(EXCLUDED_ADRS);
    Set<String> decided = new java.util.HashSet<>(listed);
    decided.addAll(EXCLUDED_ADRS);
    assertThat(decided).as("모든 ADR 은 manifest 에 넣거나 EXCLUDED_ADRS 에 둔다").containsAll(adrFiles);
    assertThat(manifest.documents())
        .noneMatch(d -> d.path().contains("worklog") || d.path().contains("handoff"));
  }

  /** ADR-0060(승인)이 ADR-0052 결정 2(상품준비 뒤 고객센터 취소)만 대체 — 결정 1(고객 자율 취소 마감)은 그대로(2026-11-15). */
  @Test
  void approvedAdr0060SupersedesOnlyDecision2Of0052() {
    var adr52 = rows.stream().filter(r -> r.path().startsWith("docs/adr/0052-")).toList();
    assertThat(adr52)
        .filteredOn(r -> r.lifecycleStatus().equals("SUPERSEDED"))
        .extracting(ChunkRow::sectionPath)
        .containsExactly("결정 > 2");
    assertThat(adr52)
        .filteredOn(r -> "결정 > 1".equals(r.sectionPath()))
        .singleElement()
        .satisfies(r -> assertThat(r.lifecycleStatus()).isEqualTo("ACTIVE"));
    assertThat(adr52)
        .filteredOn(r -> r.lifecycleStatus().equals("SUPERSEDED"))
        .allSatisfy(r -> assertThat(r.supersededBy()).startsWith("docs/adr/0060-"));
  }

  @Test
  void commerceConfirmedCategoryPolicyKeepsAuthorityWithProposalMark() {
    var chunk =
        rows.stream()
            .filter(r -> r.path().equals("docs/business/Commerce-business-logic.md"))
            .filter(r -> r.content().contains("고객 분류는 1·2단계"))
            .findFirst()
            .orElseThrow();
    assertThat(chunk.authority()).isEqualTo("CONFIRMED");
    assertThat(chunk.lifecycleStatus()).isEqualTo("SUPERSESSION_PROPOSED");
    assertThat(chunk.proposedSupersededBy())
        .containsExactly("docs/adr/0015-category-tree-follows-reference-data.md");
  }

  /**
   * ADR-0049(승인)가 업무 원문 OMS 8.2 결품 처리를 바꿨다 — 그 문단만 SUPERSEDED(manifest override, 2026-11-09 A13).
   */
  @Test
  void omsShortagePreselectionIsSupersededByAdr0049Only() {
    var oms =
        rows.stream().filter(r -> r.path().equals("docs/business/OMS-business-logic.md")).toList();
    assertThat(oms)
        .filteredOn(r -> r.content().contains("주문 당시 고객은 `품절 상품만 취소` 또는 `전체 취소`를 선택한다"))
        .isNotEmpty()
        .allSatisfy(
            r -> {
              assertThat(r.lifecycleStatus()).isEqualTo("SUPERSEDED");
              assertThat(r.supersededBy())
                  .isEqualTo("docs/adr/0049-shortage-always-partial-cancel.md");
            });
    // 이웃 절(8.3 취소와 인계 경합)은 그대로
    assertThat(oms)
        .filteredOn(r -> r.sectionPath() != null && r.sectionPath().contains("8.3 취소와 인계 경합"))
        .isNotEmpty()
        .allSatisfy(r -> assertThat(r.lifecycleStatus()).isEqualTo("ACTIVE"));
  }

  @Test
  void commerceAppendixCIsExcludedFromPhase1() {
    assertThat(rows)
        .filteredOn(r -> r.sectionPath() != null && r.sectionPath().startsWith("부록 C."))
        .isNotEmpty()
        .allSatisfy(r -> assertThat(r.lifecycleStatus()).isEqualTo("EXCLUDED_FROM_PHASE1"));
  }

  @Test
  void implementationRecordDateIsSeparateFromAuthority() {
    // 절마다 자기 "구현 상태 (날짜…)" — 1.4 는 2026-10-31, 2.0.x(슬라이스 6, 2026-11-07 추가)는 2026-11-07
    var recorded =
        rows.stream()
            .filter(r -> r.path().endsWith("order-checkout-saga.md") && r.implRecordDate() != null)
            .toList();
    assertThat(recorded).isNotEmpty();
    assertThat(recorded)
        .filteredOn(r -> r.sectionPath() != null && r.sectionPath().contains("1.4 구현 상태"))
        .isNotEmpty()
        .allSatisfy(r -> assertThat(r.implRecordDate()).isEqualTo("2026-10-31"));
    assertThat(recorded)
        .filteredOn(r -> r.sectionPath() != null && r.sectionPath().contains("2.0.1 구현 상태"))
        .isNotEmpty()
        .allSatisfy(r -> assertThat(r.implRecordDate()).isEqualTo("2026-11-07"));
    // 지위는 날짜로 바뀌지 않는다
    assertThat(recorded)
        .extracting(ChunkRow::implRecordDate)
        .containsOnly("2026-10-31", "2026-11-07");
  }

  @Test
  void adrAuthorityComesFromStatusLine() {
    assertThat(rows)
        .filteredOn(r -> r.path().startsWith("docs/adr/0026-"))
        .allSatisfy(r -> assertThat(r.authority()).isEqualTo("CONFIRMED"));
    assertThat(rows)
        .filteredOn(r -> r.path().startsWith("docs/adr/0025-"))
        .allSatisfy(r -> assertThat(r.authority()).isEqualTo("PROPOSAL"));
  }

  @Test
  void chunkIdsAreUniqueAndVersionsAreNotInvented() {
    assertThat(rows).extracting(ChunkRow::chunkId).doesNotHaveDuplicates();
    assertThat(rows)
        .filteredOn(r -> r.path().equals("docs/business/glossary.md"))
        .allSatisfy(r -> assertThat(r.documentVersion()).isNull());
    assertThat(rows)
        .filteredOn(r -> r.path().equals("docs/business/WMS-business-logic.md"))
        .allSatisfy(r -> assertThat(r.documentVersion()).isEqualTo("8.1"));
    assertThat(rows).allSatisfy(r -> assertThat(r.embedding()).hasSize(384));
  }
}
