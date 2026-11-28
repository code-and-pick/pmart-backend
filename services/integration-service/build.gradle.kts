// integration-service: 문서 저장소 GitHub code-and-pick/pmart-all-docs 와 웹훅으로 연결된 흐름(ADR-0095, 2026-11-28).
// push·delete 웹훅 → 바뀐 파일 확인 → 파일을 GitHub API 로 가져와 내용 추출 → 청킹 서비스로 전달. 흐름은 Spring Integration(이 서비스에만).
// DB·Kafka 없음. 웹훅 입구는 서명(X-Hub-Signature-256)으로 확인한다(서비스 간 경로 /internal — 게이트웨이가 막는다).
plugins {
    id("pmart.spring-service")
}

pmartOpenApi {
    basePackage.set("com.pmart.integration")
    // 입구는 Spring Integration HTTP 게이트웨이가 맡는다(생성 컨트롤러 인터페이스를 쓰지 않음). 계약 파일은 검증만
    enabled.set(false)
}

dependencies {
    implementation(project(":libraries:common-web"))
    implementation(project(":libraries:common-observability"))
    implementation(libs.bundles.service.observability)
    implementation(libs.spring.boot.starter.integration)
    implementation(libs.spring.integration.http)

    testImplementation(project(":libraries:common-test"))
    testImplementation(libs.spring.integration.test)
    "integrationTestImplementation"(project(":libraries:common-test"))
    "integrationTestImplementation"(libs.spring.boot.starter.integration)
    "integrationTestImplementation"(libs.spring.integration.http)
    "integrationTestImplementation"(libs.wiremock.spring.boot) // GitHub API·청킹 서비스 흉내
}

jib {
    container {
        mainClass = "com.pmart.integration.IntegrationServiceApplication"
    }
}
