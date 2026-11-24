-- 로컬 전용: 서비스별 데이터베이스와 복제 권한. (운영은 서비스별 DB·계정 분리 + Secret Manager)
-- 사용자 pmart 는 superuser(로컬)이므로 Debezium 복제 슬롯 생성이 가능하다.
-- 서비스 DB 는 이름 규칙 {service-name 의 하이픈을 밑줄로}: order_service, mfc_inventory_service ...
-- 3단계 스캐폴딩에서 서비스가 늘어나면 여기에 한 줄씩 추가한다.
CREATE DATABASE order_service;
CREATE DATABASE mfc_inventory_service;
CREATE DATABASE product_service;
CREATE DATABASE product_content_service;
CREATE DATABASE customer_service;
CREATE DATABASE cart_service;
CREATE DATABASE storefront_service;
CREATE DATABASE pricing_service;
CREATE DATABASE order_orchestration_service;
CREATE DATABASE claim_service;
CREATE DATABASE payment_service;
CREATE DATABASE mfc_fulfillment_service;
CREATE DATABASE mfc_capacity_service;
CREATE DATABASE mfc_location_service;
CREATE DATABASE mfc_quality_service;
CREATE DATABASE mfc_inbound_service;
CREATE DATABASE delivery_serviceability_service;
CREATE DATABASE delivery_capacity_service;
CREATE DATABASE rider_service;
CREATE DATABASE delivery_dispatch_service;
CREATE DATABASE delivery_execution_service;
CREATE DATABASE delivery_tracking_service;
CREATE DATABASE auth_service;
CREATE DATABASE rider_auth_service;
CREATE DATABASE notification_service;
CREATE DATABASE audit_service;
CREATE DATABASE approval_service;
CREATE DATABASE case_service;
CREATE DATABASE review_service;
CREATE DATABASE promotion_service;
CREATE DATABASE center_master_service;
CREATE DATABASE cdc_inventory_service;
CREATE DATABASE cdc_location_service;
