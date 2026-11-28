// embedding-service: ES 청크 → Ollama qwen3-embedding → 자기 pgvector(ADR-0098, 2026-11-28). ES-First 하이브리드 RAG 의 "ES → 임베딩 →
// pgvector 후 적재"(계획 §1-2 단계 3·4). chunking-service 가 문서 하나를 ES 에 반영하면 알려 오고, 이 서비스가 ES 의 ACTIVE 청크를 읽어
// (본문 해시 캐시에 없을 때만) 임베딩해 같은 chunkId 로 pgvector 에 넣는다. spring-ai-ollama 는 새 RAG 에서 이 서비스에만.
// DB = RAG 전용 pgvector(rag-pgvector, PC 2)의 embedding_service. Kafka 없음.
plugins {
    id("pmart.spring-service")
}

pmartOpenApi {
    basePackage.set("com.pmart.embedding")
    enabled.set(false) // 서비스 간 경로 하나 — 컨트롤러가 직접(계약 파일은 검증만)
}

dependencies {
    implementation(project(":libraries:common-web"))
    implementation(project(":libraries:common-observability"))
    implementation(libs.bundles.service.data)
    implementation(libs.bundles.service.observability)
    implementation(platform(libs.spring.ai.bom))
    implementation(libs.spring.ai.model) // EmbeddingModel 추상화
    implementation(libs.spring.ai.ollama) // Ollama qwen3-embedding(자동 설정 starter 없이 — EmbeddingConfig)

    "integrationTestImplementation"(libs.spring.boot.starter.jdbc)
    "integrationTestImplementation"(platform(libs.spring.ai.bom))
    "integrationTestImplementation"(libs.spring.ai.model)
}

jib {
    container {
        mainClass = "com.pmart.embedding.EmbeddingServiceApplication"
    }
}
