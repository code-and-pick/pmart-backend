// PMart Backend — Gradle Multi-Project (Kotlin DSL)
// 규칙: .claude/rules/gradle-build.md. 서브프로젝트는 아래에 명시적으로 등록한다.

rootProject.name = "pmart-backend"

pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Java Toolchain 자동 프로비저닝 (JDK가 없을 때만 다운로드). 로컬 JDK 21이 있으면 그것을 쓴다.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        // Confluent Schema Registry 클라이언트·JSON Schema serializer (common-messaging에서만 사용)
        maven {
            url = uri("https://packages.confluent.io/maven/")
            content { includeGroupByRegex("io\\.confluent.*") }
        }
    }
    // versionCatalogs: gradle/libs.versions.toml 이 기본 'libs' 카탈로그로 자동 등록된다.
}

// ---------------------------------------------------------------------------
// 서브프로젝트 등록. 디렉토리 규칙: services/{name}, platform-services/{name}, libraries/{name}
// 새 서비스는 해당 목록에 한 줄 추가. 와일드카드 자동 등록은 쓰지 않는다(명시성 우선).
// ---------------------------------------------------------------------------

val libraries = listOf<String>(
    "common-web",
    "common-observability",
    "common-messaging",
    "common-security",
    "common-jwt", // 발급자 표·발급자↔사용자 종류 짝 규칙(ADR-0054) — common-security(서블릿)·api-gateway(리액티브) 공용
    "common-test",
    // 2단계에서 추가: "common-web", "common-messaging", "common-security", "common-observability", "common-test"
)

val platformServices = listOf<String>(
    "api-gateway",
    "config-server", // ADR-0027
    "mail-provisioner", // 사서함 프로비저닝 사이드카(ADR-0056 결정 10·D9) — PC 5, LAN 포트 없음
    "eureka-server", // ADR-0032
)

val services = listOf<String>(
    "cdc-inventory-service",
    "cdc-location-service",
    "cdc-outbound-service",
    "center-master-service",
    "case-service",
    "review-service", // 리뷰(2026-11-16 1차 구현 — ADR-0063)
    "promotion-service", // 쿠폰·기간 할인(2026-11-20 활성 — ADR-0082)
    "approval-service",
    "ai-assistant-service", // 1차 RAG 앞당김(ADR-0036)
    "customer-assistant-service", // 고객앱 RAG(ADR-0051)
    "backoffice-bff",
    "audit-service",
    "notification-service",
    "auth-service",
    "rider-auth-service", // 라이더 Google 로그인·라이더 토큰(ADR-0054 E)
    "employee-auth-service", // 직원 로그인(Keycloak 기밀 클라이언트)·직원 토큰·직원 역할 정본(ADR-0056)
    "hr-service", // 직원 등록·인사 정보·계정 발급(사서함·Keycloak·안내 메일, ADR-0056)
    "delivery-tracking-service",
    "delivery-execution-service",
    "delivery-dispatch-service",
    "rider-service",
    "delivery-capacity-service",
    "delivery-serviceability-service",
    "mfc-inbound-service",
    "mfc-quality-service",
    "mfc-location-service",
    "mfc-capacity-service",
    "mfc-fulfillment-service",
    "mfc-inventory-service",
    "payment-service",
    "claim-service",
    "fulfillment-routing-service",
    "order-orchestration-service",
    "pricing-service",
    "storefront-service",
    "search-service",
    "cart-service",
    "customer-service",
    "product-content-service",
    "product-service",
    "order-service",
    // 3단계에서 추가. 1단계 서비스 목록은 docs/architecture/architecture-design.md §8
)

libraries.forEach { name ->
    include(":libraries:$name")
    project(":libraries:$name").projectDir = file("libraries/$name")
}
platformServices.forEach { name ->
    include(":platform-services:$name")
    project(":platform-services:$name").projectDir = file("platform-services/$name")
}
services.forEach { name ->
    include(":services:$name")
    project(":services:$name").projectDir = file("services/$name")
}
