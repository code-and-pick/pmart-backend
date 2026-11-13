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

    testImplementation(project(":libraries:common-test"))
    "integrationTestImplementation"(project(":libraries:common-test"))
    "integrationTestImplementation"(libs.spring.boot.starter.jdbc) // 시험에서 Outbox 행 확인
}
