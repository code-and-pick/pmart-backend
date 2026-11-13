# [pmart-backend → pmart-clients] 안내: 개발 단계 고객앱 팀 배포 방법 (2026-11-04)

- 성격: **안내(제안).** 백엔드는 pmart-clients 를 수정하지 않는다. 방법 선택과 실행은 클라이언트 팀이 한다.
- 근거는 2026-11-04 백엔드 쪽 확인 결과다. 클라이언트 저장소는 읽기만 했다.
  - 연결된 폰(SM-A536N)과 이 PC(192.168.0.32)의 망·경로를 확인했다.
  - `.run/customer-lan.run.xml`, `apps/customer_app/android/app/build.gradle.kts`, `docs/apps/customer-app.md` 를 읽었다.
- 아래 빌드 명령은 실행 설정을 읽어 정리한 것이다. **백엔드에서 직접 돌려 보지는 않았다.**

## 1. 지금 앱이 서버에 닿는 경로 (확인한 사실)
- 폰은 Wi-Fi 망 `192.168.10.0/24`(폰 192.168.10.3)에 있다. 백엔드 PC 들은 이더넷 망 `192.168.0.0/24` 에 있다. **두 망은 다르다.**
- 폰의 기본 경로는 `0.0.0.0/0 → 192.168.10.11`(Wi-Fi 공유기)이다. 폰에서 `192.168.0.40:8080`(PC4 게이트웨이)으로의 TCP 연결은 **열림**이다.
- 이더넷 망에서는 192.168.10.x 로 들어가지 못한다(ping 100% 손실, 경로 없음). 이로 보아 공유기가 NAT 모드인 것으로 추정한다. 공유기 설정 화면은 보지 않았다.
- 그래서 게이트웨이 로그에는 폰 주소가 아니라 **공유기의 이더넷 쪽 주소**가 출처로 찍힐 것이다.
- USB 는 설치·디버그(flutter run)에만 쓰인다. `adb reverse`·`adb forward` 규칙은 없다. 백엔드 PC 에는 :8080 을 받는 프로세스가 없다.

## 2. 배포 방식을 정할 때 알아야 할 것
1. **서버 주소는 빌드할 때 앱에 들어간다.** `--dart-define=PMART_API_BASE_URL=http://192.168.0.40:8080/api/v1` 이다.
   - 이 APK 는 **사무실 Wi-Fi(공유기 경유로 PC4 에 닿는 망)에 붙은 폰에서만** 동작한다. 사무실 밖이나 LTE 에서는 서버에 닿지 않는다.
   - 주소가 바뀌면 다시 빌드해서 다시 나눠야 한다.
2. **release 도 빌드한 PC 의 디버그 키로 서명된다.** `build.gradle.kts:36` 의 `signingConfig = signingConfigs.getByName("debug")` 때문이다.
   - Google 로그인은 Android OAuth 클라이언트에 등록된 **패키지 `com.pmart.customer_app` + 서명 SHA-1** 이 맞아야 된다(`docs/apps/customer-app.md:37`).
   - **한 PC 에서 만든 APK 하나**를 나누면, 그 PC 의 SHA-1 만 등록돼 있으면 모두 로그인된다.
   - **팀원이 각자 빌드하면** SHA-1 이 사람마다 달라서, 각자 등록하지 않으면 로그인이 실패한다.
   - 서명 키가 바뀐 APK 는 기존 앱 위에 덮어 설치되지 않는다(지우고 다시 설치).
3. **빌드에 `.secrets/customer.env` 가 필요하다**(`--dart-define-from-file`).
   - 각자 빌드하려면 이 파일을 나눠야 한다.
   - APK 안에 이 값이 들어가므로 APK 를 팀 밖이나 공개 장소에 올리지 않는다.

## 3. 방법 비교

| 방법 | 장점 | 주의 |
|---|---|---|
| **A. APK 직접 공유 (지금 추천)** | 바로 가능, 비용 없음 | 업데이트 알림 없음, "출처를 알 수 없는 앱" 설치 허용 필요 |
| B. Firebase App Distribution | 테스터 초대·새 버전 알림·버전 기록 | Firebase 프로젝트 필요, APK 를 외부 서비스에 올림(팀 결정) |
| C. Google Play 내부 테스트 | 실제 출시와 같은 설치·업데이트 | 개발자 계정(25달러), Play 앱 서명 키 SHA-1 을 OAuth 에 새로 등록 |
| D. 각자 Android Studio 빌드 | 앱을 고치는 개발자에게 필요 | `.secrets` 배포, 사람마다 SHA-1 등록 |

**제안**: 지금은 A 로 하고, 업데이트가 잦아지면 B, 나중에 CI 빌드·배포로 옮긴다.

## 4. A 방식 순서 (앱을 빌드하는 PC 한 대에서)
1. 그 PC 의 디버그 서명 SHA-1 이 Google Cloud 콘솔의 Android OAuth 클라이언트에 등록돼 있는지 확인한다.
   - 확인 방법: `apps/customer_app/android` 에서 `./gradlew signingReport`
2. 빌드한다(`apps/customer_app` 에서 실행):
   ```bash
   flutter build apk --release \
     --dart-define=PMART_ENV=local \
     --dart-define=PMART_API_BASE_URL=http://192.168.0.40:8080/api/v1 \
     --dart-define-from-file=.secrets/customer.env
   ```
   - `.secrets/customer.env` 경로는 `.run/customer-lan.run.xml` 기준이다. 실제 위치는 클라이언트 저장소에서 확인한다.
3. 결과물은 `build/app/outputs/flutter-apk/app-release.apk` 이다. 사내 메신저나 드라이브(팀 내부)로 공유한다. 파일 이름에 빌드 날짜와 커밋을 붙이면 버전을 구분하기 쉽다.
4. 팀원 안내:
   - "출처를 알 수 없는 앱 설치"를 허용하고 설치한다.
   - **사무실 Wi-Fi 에 연결한 상태**로 쓴다.
   - 서명 키가 다른 빌드에서 넘어올 때는 기존 앱을 지우고 설치한다.
5. 서버 쪽 전제:
   - PC4 의 LAN 게이트웨이(192.168.0.40:8080)와 관련 서비스가 떠 있어야 한다.
   - 백엔드가 LAN 을 다시 배포하는 동안에는 일시적으로 503 이 날 수 있다.

## 5. 남은 결정 (클라이언트 팀)
- A·B·C 중 어떤 방식으로 할지, 그리고 APK 를 누가 언제 빌드할지(빌드 PC 한 대로 고정하면 SHA-1 이 하나로 유지된다).
- 사무실 밖에서도 써야 하는지. 써야 한다면 VPN 이나 외부 공개 게이트웨이처럼 백엔드 쪽 결정이 필요하다 — 따로 요청해 달라.
- release 전용 서명 키를 만들지(장기적으로 디버그 키 대신).
