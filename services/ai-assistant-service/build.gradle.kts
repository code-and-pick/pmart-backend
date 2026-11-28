// ai-assistant-service: 내부 운영자용 정책·업무 절차 질의응답(RAG, 1차 — ADR-0036). 저장소 문서의 사본 색인과 답변 기록만 소유한다.
// Spring AI·pgvector 는 이 서비스에만(.claude/rules/gradle-build.md). 모델 제공자는 LAN Ollama 만(ADR-0051) — 외부 유료 API 모듈은 넣지 않는다.
plugins {
    id("pmart.spring-service")
}

pmartOpenApi {
    basePackage.set("com.pmart.assistant")
}

dependencies {
    implementation(project(":libraries:common-web"))
    implementation(project(":libraries:common-observability"))
    implementation(project(":libraries:common-security"))
    implementation(project(":libraries:common-messaging")) // ADR-0092: 승인 반영(ApprovalDecided 구독·회신) — outbox 는 쓰지 않는다
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
    "integrationTestImplementation"(libs.spring.ai.ollama) // GenerationEvalIT 가 LAN 과 같은 채팅 모델을 만든다(OllamaModels.chat)
    "integrationTestImplementation"(libs.spring.boot.starter.jdbc)
}

// 임베딩 비교 평가(ADR-0051 D3): -PevalOllamaModel=<모델> [-PevalOllamaUrl=http://localhost:11434] 이면 RetrievalEvalIT 가
// 해시 기준선 대신 그 Ollama 모델로 색인·검색한다 — 보고서 build/reports/eval/<모델>/. 없으면 평소 시험 그대로.
tasks.named<Test>("integrationTest") {
    val evalModel = providers.gradleProperty("evalOllamaModel").orElse("")
    val evalUrl = providers.gradleProperty("evalOllamaUrl").orElse("http://localhost:11434")
    inputs.property("evalOllamaModel", evalModel)
    systemProperty("pmart.eval.ollama-model", evalModel.get())
    systemProperty("pmart.eval.ollama-url", evalUrl.get())
    // 검색 가중 조정(-PevalSweep=true) — 같은 색인으로 여러 가중을 비교(retrieval-sweep.md)
    // 생성·인용 검사 측정(-PevalChatModel=qwen3:4b) — GenerationEvalIT 가 실제 답을 받아 문장별 점수를 기록한다(2026-11-27)
    val evalChat = providers.gradleProperty("evalChatModel").orElse("")
    inputs.property("evalChatModel", evalChat)
    systemProperty("pmart.eval.chat-model", evalChat.get())
    // 기록으로 기준값만 다시 계산(-PevalGenerationSweep=true) — 모델을 부르지 않는다
    val evalGenSweep = providers.gradleProperty("evalGenerationSweep").orElse("false")
    inputs.property("evalGenerationSweep", evalGenSweep)
    systemProperty("pmart.eval.generation-sweep", evalGenSweep.get())
    val evalSweep = providers.gradleProperty("evalSweep").orElse("false")
    inputs.property("evalSweep", evalSweep)
    systemProperty("pmart.eval.sweep", evalSweep.get())
}

// 통제된 색인·정리 작업(ADR-0036). 평소 bootRun·컨테이너 기동에서는 색인하지 않는다.
// 앱을 local 프로필로 띄워 한 번 실행하고 종료한다 — 먼저 config-server(local)와 deploy/local/ai-assistant compose 를 띄운다.
//   ./gradlew :services:ai-assistant-service:ingestKnowledge -Prepo=<저장소 경로>
//   ./gradlew :services:ai-assistant-service:pruneSnapshots
val assistantMain = "com.pmart.assistant.AiAssistantServiceApplication"
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
