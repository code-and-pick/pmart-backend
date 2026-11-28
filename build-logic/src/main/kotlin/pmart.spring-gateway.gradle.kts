// platform-services/api-gateway 용: Spring Cloud Gateway Server WebFlux + Reactor Netty.
// Spring MVC starter 를 넣지 않는다 (서블릿 스택이 섞이면 Gateway 가 동작하지 않음). 2026-10-23 결정: 게이트웨이는 Netty.
// 계약 검증·OpenAPI 생성은 적용하지 않는다(게이트웨이는 업무 API 가 없다). 컨테이너 이미지는 동일하게 Jib.

import org.gradle.accessors.dm.LibrariesForLibs

val libs = the<LibrariesForLibs>()

plugins {
    java
    id("org.springframework.boot")
    id("pmart.java-conventions")
    id("pmart.container-image")
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(platform(libs.spring.cloud.bom))
    annotationProcessor(platform(libs.spring.boot.bom))
    testImplementation(platform(libs.spring.boot.bom))
    "integrationTestImplementation"(platform(libs.spring.boot.bom))

    implementation(libs.spring.cloud.starter.gateway)
    // Config 서버 클라이언트(ADR-0027). application.yml 이 optional:configserver: + enabled=false 로 기본 꺼 둔다(LAN 만 켬)
    implementation(libs.spring.cloud.starter.config)
    // Vault 클라이언트(Spring Cloud Vault — 서비스가 자기 비밀을 Vault config/<앱>,lan 에서 직접 읽는다, {cipher} 대체).
    // common-observability 가 spring.cloud.vault.enabled=false 기본값을 넣고, LAN 만 SPRING_CONFIG_IMPORT·SPRING_CLOUD_VAULT_* 환경변수로 켠다
    implementation(libs.spring.cloud.starter.vault.config)
    // Eureka 클라이언트 + Spring Cloud LoadBalancer(ADR-0032). 설정 저장소 application.yml 이 기본 끄고 lan 프로필만 켠다
    implementation(libs.spring.cloud.starter.eureka.client)
    // 설정 변경 알림(Spring Cloud Bus, Kafka 토픽 springCloudBus — ADR-0033). common-observability 가 기본 끄고 lan 프로필만 켠다
    implementation(libs.spring.cloud.starter.bus.kafka)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.oauth2.resource.server)
    implementation(libs.jspecify)
    annotationProcessor(libs.spring.boot.configuration.processor)

    testImplementation(libs.spring.boot.starter.test)
    "integrationTestImplementation"(libs.bundles.test.integration)
}

springBoot {
    buildInfo()
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("${project.name}.jar")
    layered { enabled.set(true) }
}
