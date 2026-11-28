// rag-query-service: 새 RAG 질문 검색(ADR-0099, 2026-11-28) — ES-First 하이브리드의 질문 쪽.
// ES BM25 먼저 → SearchConfidenceEvaluator(CPU 규칙: 핵심어 비율·상대 점수 차 등)가 안전하면 ES 에서 끝(상위 3 카드),
// 아니면 embedding-service 벡터 검색과 RRF 로 합치고 벡터 코사인으로 답변 불가 판정. ③ 생성(LLM)은 아직 없음. DB·Kafka 없음.
plugins {
    id("pmart.spring-service")
}

pmartOpenApi {
    basePackage.set("com.pmart.ragquery")
    enabled.set(false) // 서비스 간 경로 하나 — 컨트롤러가 직접(계약 파일은 검증만)
}

dependencies {
    implementation(project(":libraries:common-web"))
    implementation(project(":libraries:common-observability"))
    implementation(libs.bundles.service.observability)
}

jib {
    container {
        mainClass = "com.pmart.ragquery.RagQueryServiceApplication"
    }
}
