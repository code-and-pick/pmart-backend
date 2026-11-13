// delivery-dispatch-service: docs/architecture/service-map.md 참조. 업무 규칙은 domain/application 패키지에.
plugins {
    id("pmart.spring-service")
}

pmartOpenApi {
    basePackage.set("com.pmart.delivery.dispatch")
}

dependencies {
    implementation(project(":libraries:common-web"))
    implementation(project(":libraries:common-observability"))
    implementation(project(":libraries:common-security"))
    implementation(project(":libraries:common-messaging"))
    implementation(libs.bundles.service.kafka)
    implementation(libs.bundles.service.data)
    implementation(libs.bundles.service.observability)
    implementation(libs.ortools.java) // AI 추천 배차 임시 계산(CP-SAT, ADR-0058). 이 의존이 있으면 Native 금지 — JVM(ADR-0053)

    testImplementation(project(":libraries:common-test"))
    "integrationTestImplementation"(project(":libraries:common-test"))
    "integrationTestImplementation"(libs.spring.boot.starter.jdbc) // 시험에서 Outbox 행 확인
    "integrationTestImplementation"(libs.wiremock.spring.boot) // rider·tracking·order·center·serviceability 응답 고정
}
