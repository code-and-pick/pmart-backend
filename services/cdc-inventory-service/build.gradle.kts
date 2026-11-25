// cdc-inventory-service: docs/architecture/service-map.md 참조. 업무 규칙은 domain/application 패키지에.
plugins {
    id("pmart.spring-service")
}

pmartOpenApi {
    basePackage.set("com.pmart.cdc.inventory")
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
    "integrationTestImplementation"(libs.spring.boot.starter.jdbc) // 시험에서 투영·취급 행 확인
    "integrationTestImplementation"(libs.bundles.service.kafka) // 시험에서 상품 이벤트를 토픽에 흘림
    "integrationTestImplementation"(libs.wiremock.spring.boot) // cdc-location 위치 조회 응답 고정(피킹 선반 먼저)
}
