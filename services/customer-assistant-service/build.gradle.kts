// customer-assistant-service: 고객앱 고객센터 AI 상담(RAG, ADR-0051). 승인된 고객 안내 문서의 사본 색인과 고객 답변 기록만 소유한다.
// 운영자용 ai-assistant-service 에서 출발한 복사본(공용 라이브러리는 아직 빼지 않음 — CLAUDE.md "운영자용과의 관계").
// Spring AI·pgvector 는 RAG 서비스에만(.claude/rules/gradle-build.md). 모델 제공자는 LAN Ollama 만(ADR-0051).
plugins {
    id("pmart.spring-service")
}

pmartOpenApi {
    basePackage.set("com.pmart.customerassistant")
}

dependencies {
    implementation(project(":libraries:common-web"))
    implementation(project(":libraries:common-observability"))
    implementation(project(":libraries:common-security"))
    implementation(project(":libraries:common-messaging")) // ADR-0094: 고객 RAG 문서 승인 반영(ApprovalDecided 구독·회신) — outbox 는 쓰지 않는다
    implementation(libs.bundles.service.kafka)
    implementation(libs.bundles.service.data)
    implementation(libs.bundles.service.observability)
    implementation(platform(libs.spring.ai.bom))
    implementation(libs.spring.ai.model)
    implementation(libs.spring.ai.ollama) // ADR-0051: LAN Ollama(자동 설정 없이 AssistantConfig 가 만든다)

    testImplementation(project(":libraries:common-test"))
    "integrationTestImplementation"(project(":libraries:common-test"))
    "integrationTestImplementation"(platform(libs.spring.ai.bom))
    "integrationTestImplementation"(libs.spring.ai.model)
    "integrationTestImplementation"(libs.spring.boot.starter.jdbc)
}

// 통제된 색인·정리 작업(ADR-0036). 평소 bootRun·컨테이너 기동에서는 색인하지 않는다.
// 앱을 local 프로필로 띄워 한 번 실행하고 종료한다 — 먼저 config-server(local)와 deploy/local/ai-assistant compose 를 띄운다.
//   ./gradlew :services:customer-assistant-service:ingestKnowledge -Prepo=<저장소 경로>
//   ./gradlew :services:customer-assistant-service:pruneSnapshots
val assistantMain = "com.pmart.customerassistant.CustomerAssistantServiceApplication"
tasks.register<JavaExec>("ingestKnowledge") {
    group = "pmart"
    description = "manifest 문서를 색인해 새 스냅숏을 활성화한다 (-Prepo=<저장소 경로>)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set(assistantMain)
    val repo = providers.gradleProperty("repo")
    val profile = providers.gradleProperty("profile").orElse("local")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "--spring.profiles.active=${profile.get()}",
            "--spring.main.web-application-type=none",
            "--pmart.assistant.ingest.run=true",
            "--pmart.assistant.ingest.repo=${repo.orNull ?: throw GradleException("-Prepo 가 필요하다")}",
        )
    })
}
tasks.register<JavaExec>("pruneSnapshots") {
    group = "pmart"
    description = "유예가 지난 RETIRED·FAILED 스냅숏 중 처리 중 요청이 고정하지 않은 것만 지운다"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set(assistantMain)
    val profile = providers.gradleProperty("profile").orElse("local")
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "--spring.profiles.active=${profile.get()}",
            "--spring.main.web-application-type=none",
            "--pmart.assistant.ingest.prune=true",
        )
    })
}
