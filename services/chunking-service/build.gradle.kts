// chunking-service: 문서 청킹 → Elasticsearch RAG 색인(ADR-0096, 2026-11-28). ES-First 하이브리드 RAG 의 "청킹 → ES 선 적재"(계획 §1-2 단계 2).
// integration-service 가 보낸 추출 내용(ExtractedDocument)을 Spring AI 마크다운 리더로 제목별로 나누고(긴 절은 TokenTextSplitter),
// 접근 범위별 색인(rag-operator·rag-customer, es-search)에 문서 단위로 바꿔 넣는다. DB·Kafka 없음. ES 는 REST(클라이언트 라이브러리 없음, ADR-0031).
plugins {
    id("pmart.spring-service")
}

pmartOpenApi {
    basePackage.set("com.pmart.chunking")
    enabled.set(false) // 서비스 간 경로 하나 — 컨트롤러가 직접(계약 파일은 검증만)
}

dependencies {
    implementation(project(":libraries:common-web"))
    implementation(project(":libraries:common-observability"))
    implementation(libs.bundles.service.observability)
    implementation(platform(libs.spring.ai.bom))
    implementation(libs.spring.ai.markdown.reader) // MarkdownDocumentReader + spring-ai-commons(TokenTextSplitter)

    testImplementation(project(":libraries:common-test"))
    "integrationTestImplementation"(project(":libraries:common-test"))
}

jib {
    container {
        mainClass = "com.pmart.chunking.ChunkingServiceApplication"
    }
}
