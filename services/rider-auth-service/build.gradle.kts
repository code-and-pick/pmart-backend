// rider-auth-service: 라이더 Google 로그인·라이더 토큰 발급자(ADR-0054 E·F, ADR-0055). docs/architecture/service-map.md 참조.
// Kafka 를 쓰지 않는다(발행·구독 사건 없음 — 라이더 업무 정보는 rider-service 정본).
plugins {
    id("pmart.spring-service")
}

pmartOpenApi {
    basePackage.set("com.pmart.riderauth")
}

dependencies {
    implementation(project(":libraries:common-web"))
    implementation(project(":libraries:common-observability"))
    implementation(project(":libraries:common-security"))
    implementation(project(":libraries:common-messaging")) // SubjectBlocked 발행(outbox) — 로그아웃 즉시 차단, 2026-11-13
    implementation(libs.bundles.service.kafka)
    implementation(libs.bundles.service.data)
    implementation(libs.bundles.service.observability)

    testImplementation(project(":libraries:common-test"))
    "integrationTestImplementation"(project(":libraries:common-test"))
    "integrationTestImplementation"(project(":libraries:common-security")) // 시험이 JWT·Nimbus 를 직접 쓴다
    "integrationTestImplementation"(libs.spring.boot.starter.jdbc) // 시험에서 DB 행 확인
}
