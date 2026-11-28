# 배포 스크립트(PC 1 render.sh)용 — 서비스 AppRole 의 role-id 읽기·secret-id 발급만.
# 역할 설정(CIDR·정책) 자체는 바꾸지 못한다(그건 pmart-admin). 비밀 값은 읽지 못한다.
path "auth/approle/role/+/role-id" {
  capabilities = ["read"]
}
path "auth/approle/role/+/secret-id" {
  capabilities = ["update"]
}
