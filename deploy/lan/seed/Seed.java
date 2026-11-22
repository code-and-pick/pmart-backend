import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import javax.imageio.ImageIO;

/**
 * LAN·로컬 시험 데이터 적재(ADR-0014: 승인 경로 API 로만, DB 직접 입력 없음). JDK 만 쓴다.
 *
 * <pre>
 * java deploy/lan/seed/Seed.java                                  # LAN: 게이트웨이 = placement.conf 의 api-gateway PC
 * </pre>
 *
 * 직원 토큰은 employee-auth-service 시험용 교환(ADR-0056, 2026-11-13 — auth-service 운영자 로그인을 없앰): Keycloak(pmart-dev-cli)
 * 비밀번호 로그인 → {@code /api/v1/internal/dev-token-exchange}. 필요: {@code deploy/lan/.secrets/keycloak-admin-password}·
 * {@code keycloak-claude-dev-password}(또는 PMART_SEED_KEYCLOAK_ADMIN_PASSWORD·PMART_SEED_DEV_PASSWORD). 주소는 --keycloak·
 * --employee-auth 로 바꾼다. 로컬 프로필에는 Keycloak 이 없어 LAN 에서만 돈다.
 *
 * 조회도 MD 토큰으로 한다(공개 경로가 배포되기 전 게이트웨이에서도 동작).
 *
 * 순서: 관리자(claude-dev) 토큰 → 시험 직원(seed-md: MD·센터, seed-approver: 승인 역할) 역할 맞춤 → 카테고리 트리(PRODUCT 승인) → 배송 권역
 * (SERVICEABILITY 승인) → 상품(PRODUCT 승인, 바코드로 이미 있는지 확인) → 콘텐츠·대표 이미지(DISPLAY 승인) → 기본가(PRICE 승인) →
 * MFC 취급 ON. 각 단계는 결과가 이미 있으면 건너뛴다(다시 돌려도 안전).
 *
 * <p>비밀번호·토큰은 출력하지 않는다. 시험 직원의 Keycloak 비밀번호는 실행마다 무작위로 정한다(저장하지 않음). 계정은 만들지 않는다 —
 * Keycloak·employee-auth-service 에 있어야 한다(새 직원은 hr-service 로 등록).
 */
public class Seed {

  static final String SOURCE = "pmart-seed";
  static final String MD_LOGIN = "seed-md";
  static final String APPROVER_LOGIN = "seed-approver";
  static final List<String> APPROVER_ROLES =
      List.of(
          "PRODUCT_APPROVER",
          "DISPLAY_APPROVER",
          "PRICE_APPROVER",
          "SERVICEABILITY_APPROVER",
          "CENTER_MASTER_APPROVER",
          "INVENTORY_APPROVER");

  final HttpClient http =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(5))
          .build();
  final String api;
  final Map<String, Object> data;
  String md;
  String approver;
  String keycloak = "http://192.168.0.101:8180";
  String employeeAuth = "http://192.168.0.101:8511";
  String kcAdminPassword;
  String devPassword;
  /** --initial-stock: 초기 재고 반입(한 번만 — 다시 돌리면 그만큼 더 들어간다). 추가 SKU 는 --initial-stock-skus a,b */
  boolean initialStock;
  List<String> extraStockSkus = List.of();

  Seed(String gateway, Map<String, Object> data) {
    this.api = gateway.replaceAll("/+$", "") + "/api/v1";
    this.data = data;
  }

  public static void main(String[] args) throws Exception {
    Path root = Path.of(".").toAbsolutePath().normalize();
    String gateway = null;
    String keycloak = null;
    String employeeAuth = null;
    boolean initialStock = false;
    List<String> extraSkus = List.of();
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--gateway" -> gateway = args[++i];
        case "--keycloak" -> keycloak = args[++i];
        case "--employee-auth" -> employeeAuth = args[++i];
        case "--initial-stock" -> initialStock = true;
        case "--initial-stock-skus" -> extraSkus = List.of(args[++i].split(","));
        default -> throw new IllegalArgumentException("알 수 없는 인자: " + args[i]);
      }
    }
    Path secrets = root.resolve("deploy/lan/.secrets");
    if (gateway == null) {
      gateway = "http://" + gatewayHost(root.resolve("deploy/lan/placement.conf")) + ":8080";
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> data =
        (Map<String, Object>)
            Json.parse(
                Files.readString(root.resolve("deploy/lan/seed/seed-data.json"), StandardCharsets.UTF_8));
    Seed s = new Seed(gateway, data);
    s.initialStock = initialStock;
    s.extraStockSkus = extraSkus;
    if (keycloak != null) {
      s.keycloak = keycloak.replaceAll("/+$", "");
    }
    if (employeeAuth != null) {
      s.employeeAuth = employeeAuth.replaceAll("/+$", "");
    }
    s.kcAdminPassword =
        readSecret(secrets, "keycloak-admin-password", "PMART_SEED_KEYCLOAK_ADMIN_PASSWORD", null);
    s.devPassword =
        readSecret(secrets, "keycloak-claude-dev-password", "PMART_SEED_DEV_PASSWORD", null);
    say("게이트웨이 " + s.api);
    s.run();
  }

  void run() throws Exception {
    String mfcId = str(data, "mfcId");
    md = staffToken(MD_LOGIN, List.of("MD"), List.of(mfcId));
    approver = staffToken(APPROVER_LOGIN, APPROVER_ROLES, List.of());

    center(mfcId);
    staffCalendar(mfcId);
    categoryTree();
    serviceability(mfcId);
    List<Map<String, Object>> products = list(data, "products");
    Map<String, String[]> ids = new LinkedHashMap<>(); // key → {productId, sku}
    for (Map<String, Object> p : products) {
      ids.put(str(p, "key"), product(p));
    }
    for (Map<String, Object> p : products) {
      content(p, ids.get(str(p, "key"))[0]);
    }
    prices(products, ids);
    handling(mfcId, ids);
    if (initialStock) {
      initialStock(mfcId, ids);
    }

    say("");
    say("완료. 시험 상품:");
    for (Map<String, Object> p : products) {
      String[] id = ids.get(str(p, "key"));
      say(String.format("  %-8s %-40s %s  %s  %,d원", str(p, "key"), str(p, "name"), id[0], id[1], num(p, "price")));
    }
    Map<String, Object> zone = map(data, "serviceability");
    Map<String, Object> in = map(zone, "inside");
    say("배송 가능 좌표 예: " + str(in, "label") + " lat " + in.get("lat") + ", lng " + in.get("lng") + " → " + mfcId);
  }

  // ---------- 직원 토큰(employee-auth-service, ADR-0056) ----------

  /** 절대 주소 요청(Keycloak·employee-auth-service 직접). form 이 있으면 x-www-form-urlencoded. */
  Res raw(String method, String url, String token, Map<String, String> form, Object json, Map<String, String> headers)
      throws Exception {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("Accept", "application/json");
    if (token != null) {
      b.header("Authorization", "Bearer " + token);
    }
    headers.forEach(b::header);
    if (form != null) {
      StringBuilder f = new StringBuilder();
      form.forEach((k, v) -> f.append(f.isEmpty() ? "" : "&").append(enc(k)).append('=').append(enc(v)));
      b.header("Content-Type", "application/x-www-form-urlencoded")
          .method(method, HttpRequest.BodyPublishers.ofString(f.toString(), StandardCharsets.UTF_8));
    } else if (json != null) {
      b.header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(Json.write(json), StandardCharsets.UTF_8));
    } else {
      b.method(method, HttpRequest.BodyPublishers.noBody());
    }
    HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    return new Res(r.statusCode(), r.body());
  }

  /** Keycloak 비밀번호 로그인(개발 클라이언트) → employee-auth-service 시험용 교환 → 직원 토큰. */
  String exchange(String username, String password) throws Exception {
    Res kc =
        raw(
            "POST",
            keycloak + "/realms/pmart-staff/protocol/openid-connect/token",
            null,
            Map.of("grant_type", "password", "client_id", "pmart-dev-cli", "username", username, "password", password),
            null,
            Map.of());
    kc.expect("Keycloak 로그인 " + username, 200);
    Res ex =
        raw(
            "POST",
            employeeAuth + "/api/v1/internal/dev-token-exchange",
            null,
            null,
            Map.of("keycloakAccessToken", str(kc.map(), "access_token")),
            Map.of(
                "Idempotency-Key", UUID.randomUUID().toString(),
                "X-Source-System", SOURCE,
                "X-Device-Id", SOURCE));
    ex.expect("직원 토큰 교환 " + username, 200);
    return str(map(ex.map(), "tokens"), "accessToken");
  }

  /** 시험 직원의 Keycloak 비밀번호를 무작위로 정하고, 역할·센터를 맞춘 뒤(관리자 claude-dev) 직원 토큰을 받는다. */
  String staffToken(String username, List<String> roles, List<String> centers) throws Exception {
    Res adm =
        raw(
            "POST",
            keycloak + "/realms/master/protocol/openid-connect/token",
            null,
            Map.of("grant_type", "password", "client_id", "admin-cli", "username", "admin", "password", kcAdminPassword),
            null,
            Map.of());
    adm.expect("Keycloak 관리자 로그인", 200);
    String kcAdmin = str(adm.map(), "access_token");
    Res users =
        raw("GET", keycloak + "/admin/realms/pmart-staff/users?exact=true&username=" + enc(username), kcAdmin, null, null, Map.of());
    users.expect("Keycloak 사용자 " + username, 200);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> found = (List<Map<String, Object>>) Json.parse(users.body());
    if (found.isEmpty()) {
      throw new IllegalStateException(username + " 이 Keycloak 에 없다 — 새 직원은 hr-service 로 등록한다");
    }
    String password = randomPassword();
    raw(
            "PUT",
            keycloak + "/admin/realms/pmart-staff/users/" + str(found.get(0), "id") + "/reset-password",
            kcAdmin,
            null,
            Map.of("type", "password", "value", password, "temporary", false),
            Map.of())
        .expect("비밀번호 설정 " + username, 204);
    String token = exchange(username, password);
    @SuppressWarnings("unchecked")
    String operatorId =
        (String) ((List<Object>) ((Map<String, Object>) found.get(0).get("attributes")).get("pmart_operator_id")).get(0);
    String admin = exchange("claude-dev", devPassword);
    Res cur = send("GET", "/employee-auth/employees/" + operatorId, admin, null, null);
    cur.expect("직원 조회 " + username, 200);
    List<String> wantRoles = roles.stream().sorted().toList();
    List<String> wantCenters = centers.stream().sorted().toList();
    @SuppressWarnings("unchecked")
    List<String> haveRoles = ((List<String>) cur.map().get("roles")).stream().sorted().toList();
    @SuppressWarnings("unchecked")
    List<String> haveCenters = ((List<String>) cur.map().get("centers")).stream().sorted().toList();
    if (!wantRoles.equals(haveRoles) || !wantCenters.equals(haveCenters) || !"ACTIVE".equals(cur.map().get("state"))) {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("state", "ACTIVE");
      body.put("roles", wantRoles);
      body.put("centers", wantCenters);
      body.put("reason", "시드 시험 직원 역할 맞춤(Seed.java)");
      send("PUT", "/employee-auth/employees/" + operatorId, admin, body, null).expect("역할 맞춤 " + username, 200);
      token = exchange(username, password);
      say("직원 역할 맞춤 " + username + " " + wantRoles);
    } else {
      say("직원 확인 " + username + " " + wantRoles);
    }
    return token;
  }

  // ---------- 승인 ----------

  /** 정본 서비스가 준 승인 요청 본문 → 변경 요청·제출(seed-md) → 승인(seed-approver). approvalId. */
  String approve(Map<String, Object> approvalRequest, String what) throws Exception {
    Res cr = send("POST", "/change-requests", md, approvalRequest, null);
    cr.expect(what + " 변경 요청", 201);
    String crId = str(cr.map(), "changeRequestId");
    Res sub =
        send("POST", "/change-requests/" + crId + "/submissions", md, null, num(cr.map(), "version"));
    sub.expect(what + " 제출", 200, 202);
    String approvalId = str(sub.map(), "resultId");
    send(
            "POST",
            "/approvals/" + approvalId + "/decisions",
            approver,
            Map.of("decision", "APPROVE", "comment", "pmart-seed 시험 데이터"),
            null)
        .expect(what + " 승인", 200, 201, 202);
    say("  승인 " + what + " → " + approvalId);
    return approvalId;
  }

  // ---------- 센터(center-master, CENTER_MASTER 승인) ----------

  /**
   * MFC 와 고객 주문 운영 시간표. 없으면 만들고, 이름·주소·좌표가 시험 데이터와 다르면 UPDATE_PROFILE 로 맞춘다(시험 위치 이전).
   * 시간표는 없을 때만 넣는다(있는 센터의 다른 시간표는 바꾸지 않는다).
   */
  void center(String mfcId) throws Exception {
    Map<String, Object> c = map(data, "center");
    Map<String, Object> profile = map(c, "profile");
    String today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).toString();
    String calendarPath = "/centers/" + mfcId + "/calendar?date=" + today + "&kind=CUSTOMER_ORDER";
    Res current = send("GET", "/centers/" + mfcId, md, null, null);
    boolean exists = current.status() == 200;
    boolean profileStale = exists && !sameLocation(current.map(), profile);
    boolean calendarMissing = !exists || send("GET", calendarPath, md, null, null).status() != 200;
    if (exists && !profileStale && !calendarMissing) {
      say("센터 " + mfcId + ": 있음(위치 같음, 고객 주문 시간표 승인됨) → 건너뜀");
      return;
    }
    List<Object> lines = new ArrayList<>();
    if (!exists) {
      lines.add(
          Map.of(
              "lineKey", mfcId + ":CREATE",
              "attributes", Map.of("changeType", "CREATE", "centerId", mfcId, "profile", profile)));
    } else if (profileStale) {
      Map<String, Object> change = new LinkedHashMap<>();
      for (String k : List.of("name", "address", "coordinates")) {
        change.put(k, profile.get(k));
      }
      lines.add(
          Map.of(
              "lineKey", mfcId + ":PROFILE",
              "attributes", Map.of("changeType", "UPDATE_PROFILE", "centerId", mfcId, "profile", change)));
      say("센터 " + mfcId + ": " + current.map().get("name") + " → " + profile.get("name") + " 로 정보 변경");
    }
    if (!calendarMissing) {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("type", "CENTER_MASTER");
      body.put("targetService", "center-master-service");
      body.put("targetRef", "CENTER-" + mfcId + "-" + System.currentTimeMillis());
      body.put("allowedActions", List.of("APPLY_CENTER_CHANGE"));
      body.put("summary", "pmart-seed 시험 센터 " + mfcId + " 위치 변경");
      body.put("lines", lines);
      approve(body, "센터 " + mfcId);
      await(
          "센터 정보 반영",
          () -> {
            Res r = send("GET", "/centers/" + mfcId, md, null, null);
            return r.status() == 200 && sameLocation(r.map(), profile);
          });
      say("센터 " + mfcId + ": " + profile.get("address") + " " + profile.get("coordinates"));
      return;
    }
    List<Object> weekly = new ArrayList<>();
    for (String d : List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")) {
      weekly.add(Map.of("dayOfWeek", d, "ranges", list(c, "customerOrderHours")));
    }
    lines.add(
        Map.of(
            "lineKey", mfcId + ":CALENDAR",
            "attributes",
                Map.of(
                    "changeType", "UPDATE_CALENDAR",
                    "centerId", mfcId,
                    "calendar", Map.of("kind", "CUSTOMER_ORDER", "weekly", weekly, "holidays", List.of()))));
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", "CENTER_MASTER");
    body.put("targetService", "center-master-service");
    body.put("targetRef", "CENTER-" + mfcId + "-" + System.currentTimeMillis());
    body.put("allowedActions", List.of("APPLY_CENTER_CHANGE"));
    body.put("summary", "pmart-seed 시험 센터 " + mfcId + (exists ? " 시간표" : " 생성·시간표"));
    body.put("lines", lines);
    approve(body, "센터 " + mfcId);
    await("센터 시간표 발효", () -> send("GET", calendarPath, md, null, null).status() == 200);
    say("센터 " + mfcId + ": 고객 주문 " + list(c, "customerOrderHours"));
  }

  /**
   * 직원 근무 시간표(STAFF). 근무를 따로 등록하지 않은 작업자의 근무 = 이 시간표(사용자 결정 2026-11-09 — 풀타임은 센터 운영시간, mfc-capacity).
   * 없을 때만 넣는다.
   */
  void staffCalendar(String mfcId) throws Exception {
    Map<String, Object> c = map(data, "center");
    String today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).toString();
    String path = "/centers/" + mfcId + "/calendar?date=" + today + "&kind=STAFF";
    if (send("GET", path, md, null, null).status() == 200) {
      say("센터 " + mfcId + ": 직원 근무 시간표 있음 → 건너뜀");
      return;
    }
    List<Object> weekly = new ArrayList<>();
    for (String d : List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")) {
      weekly.add(Map.of("dayOfWeek", d, "ranges", list(c, "staffHours")));
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", "CENTER_MASTER");
    body.put("targetService", "center-master-service");
    body.put("targetRef", "CENTER-" + mfcId + "-STAFF-" + System.currentTimeMillis());
    body.put("allowedActions", List.of("APPLY_CENTER_CHANGE"));
    body.put("summary", "pmart-seed 시험 센터 " + mfcId + " 직원 근무 시간표");
    body.put(
        "lines",
        List.of(
            Map.of(
                "lineKey", mfcId + ":STAFF",
                "attributes",
                    Map.of(
                        "changeType", "UPDATE_CALENDAR",
                        "centerId", mfcId,
                        "calendar",
                            Map.of("kind", "STAFF", "weekly", weekly, "holidays", List.of())))));
    approve(body, "센터 " + mfcId + " 직원 시간표");
    await("직원 근무 시간표 발효", () -> send("GET", path, md, null, null).status() == 200);
    say("센터 " + mfcId + ": 직원 근무 " + list(c, "staffHours"));
  }

  /** 이름·주소·좌표가 시험 데이터와 같은가. */
  static boolean sameLocation(Map<String, Object> center, Map<String, Object> profile) {
    Map<String, Object> a = map(center, "coordinates");
    Map<String, Object> b = map(profile, "coordinates");
    return java.util.Objects.equals(center.get("name"), profile.get("name"))
        && java.util.Objects.equals(center.get("address"), profile.get("address"))
        && a != null
        && Math.abs(dbl(a, "lat") - dbl(b, "lat")) < 1e-6
        && Math.abs(dbl(a, "lng") - dbl(b, "lng")) < 1e-6;
  }

  // ---------- 카테고리 트리 ----------

  void categoryTree() throws Exception {
    Res current = send("GET", "/categories/tree", md, null, null);
    if (current.status() == 200) {
      say("카테고리 트리: 발효 버전 " + current.map().get("version") + " 있음 → 건너뜀");
      return;
    }
    current.expect("카테고리 트리 조회", 404);
    Res draft = send("POST", "/category-tree-versions", md, map(data, "categoryTree"), null);
    draft.expect("카테고리 트리 초안", 201);
    say("카테고리 트리 초안 버전 " + draft.map().get("version"));
    approve(map(draft.map(), "approvalRequest"), "카테고리 트리");
    await("카테고리 트리 발효", () -> send("GET", "/categories/tree", md, null, null).status() == 200);
  }

  // ---------- 배송 권역 ----------

  /**
   * 시험 권역 = {@code serviceability}(센터 {@code mfcId}) + {@code serviceability.extraZones}(권역마다 자기 {@code mfcId} —
   * 2026-11-22 2호점 목포 MFC-MOKPO-01, 시뮬레이터 R-39). 매핑 버전은 전체 교체라 모든 시험 권역을 한 버전에 담는다.
   */
  void serviceability(String mfcId) throws Exception {
    Map<String, Object> z = map(data, "serviceability");
    List<Map<String, Object>> all = new ArrayList<>();
    Map<String, Object> main = new LinkedHashMap<>(z);
    main.put("mfcId", mfcId);
    all.add(main);
    if (z.get("extraZones") != null) {
      List<Map<String, Object>> extra = list(z, "extraZones");
      all.addAll(extra);
    }
    Map<String, Object> in = map(z, "inside");
    Res probe = check(in);
    if (probe.status() == 200) {
      Object version = probe.map().get("mappingVersion");
      boolean allThere = true;
      for (Map<String, Object> zz : all) {
        Res r = zz == main ? probe : check(map(zz, "inside"));
        allThere &=
            r.status() == 200
                && "SERVICEABLE".equals(r.map().get("result"))
                && str(zz, "mfcId").equals(r.map().get("mfcId"));
      }
      if (allThere) {
        say("배송 권역: 발효 버전 " + version + " 에 시험 권역 " + all.size() + "개 있음 → 건너뜀");
        return;
      }
      // 매핑 버전은 전체 교체라 기존 권역이 사라진다. 발효 중인 권역이 모두 seed 권역(Z-SEED-*)일 때만 바꾼다(시험 위치 이전).
      Res active = send("GET", "/serviceability-versions/" + version, md, null, null);
      active.expect("발효 중 매핑 조회", 200);
      List<Map<String, Object>> zones = list(active.map(), "zones");
      boolean seedOnly =
          !zones.isEmpty() && zones.stream().allMatch(zz -> str(zz, "zoneId").startsWith("Z-SEED-"));
      if (!seedOnly) {
        say("배송 권역: 다른 매핑(버전 " + version + ")이 발효 중 → 시험 권역을 넣지 않음");
        return;
      }
      say("배송 권역: 발효 버전 " + version + " 의 seed 권역 → 시험 권역 " + all.size() + "개로 교체");
    } else {
      probe.expect("배송 판정(발효 버전 없음 확인)", 422);
    }
    List<Object> zones = new ArrayList<>();
    List<Object> tests = new ArrayList<>();
    for (Map<String, Object> zz : all) {
      Map<String, Object> box = map(zz, "box");
      double x0 = dbl(box, "minLng"), y0 = dbl(box, "minLat"), x1 = dbl(box, "maxLng"), y1 = dbl(box, "maxLat");
      List<Object> ring =
          List.of(List.of(x0, y0), List.of(x1, y0), List.of(x1, y1), List.of(x0, y1), List.of(x0, y0));
      Map<String, Object> zone = new LinkedHashMap<>();
      zone.put("zoneId", str(zz, "zoneId"));
      zone.put("mfcId", str(zz, "mfcId"));
      zone.put("name", str(zz, "name"));
      zone.put("geometry", Map.of("type", "Polygon", "coordinates", List.of(ring)));
      zones.add(zone);
      Map<String, Object> zin = map(zz, "inside");
      tests.add(Map.of("label", str(zin, "label"), "lat", zin.get("lat"), "lng", zin.get("lng"),
          "expectedResult", "SERVICEABLE", "expectedMfcId", str(zz, "mfcId")));
      Map<String, Object> out = map(zz, "outside");
      tests.add(Map.of("label", str(out, "label"), "lat", out.get("lat"), "lng", out.get("lng"),
          "expectedResult", "OUT_OF_AREA"));
    }
    Map<String, Object> draft = new LinkedHashMap<>();
    draft.put("zones", zones);
    draft.put("changeSummary", "pmart-seed 시험 권역 " + zones.size() + "개");
    draft.put("testAddresses", tests);
    Res created = send("POST", "/serviceability-versions", md, draft, null);
    created.expect("권역 매핑 초안", 201);
    say("배송 권역 초안 버전 " + map(created.map(), "version").get("version"));
    approve(map(created.map(), "approvalRequest"), "배송 권역");
    for (Map<String, Object> zz : all) {
      Map<String, Object> zin = map(zz, "inside");
      await(
          "배송 권역 발효 " + str(zz, "zoneId"),
          () -> {
            Res r = check(zin);
            return r.status() == 200
                && "SERVICEABLE".equals(r.map().get("result"))
                && str(zz, "mfcId").equals(r.map().get("mfcId"));
          });
    }
  }

  Res check(Map<String, Object> point) throws Exception {
    Map<String, Object> address = new LinkedHashMap<>();
    address.put("roadAddress", str(point, "label"));
    address.put("lat", point.get("lat"));
    address.put("lng", point.get("lng"));
    return send(
        "POST", "/serviceability-checks", md, Map.of("address", address, "purpose", "TEST_ADDRESS"), null);
  }

  // ---------- 상품 ----------

  /** {productId, sku}. 바코드로 이미 있으면 그대로. */
  String[] product(Map<String, Object> p) throws Exception {
    String key = str(p, "key");
    Res existing = send("GET", "/barcodes/" + str(p, "barcode"), md, null, null);
    if (existing.status() == 200) {
      say("상품 " + key + ": 있음 " + existing.map().get("productId") + " " + existing.map().get("sku"));
      return new String[] {str(existing.map(), "productId"), str(existing.map(), "sku")};
    }
    Map<String, Object> product = new LinkedHashMap<>();
    product.put("internalName", str(p, "name"));
    product.put("primaryCategoryId", str(p, "categoryId"));
    product.put("categoryIds", List.of(str(p, "categoryId")));
    product.put("saleState", "ON_SALE");
    Map<String, Object> sku = new LinkedHashMap<>();
    sku.put("unitType", str(p, "unitType"));
    sku.put("unitsPerSku", num(p, "unitsPerSku"));
    sku.put("weightG", num(p, "weightG"));
    sku.put("storageType", str(p, "storageType"));
    sku.put("barcodes", List.of(str(p, "barcode")));
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("sourceSystem", SOURCE);
    body.put("sourceKey", key);
    body.put("product", product);
    body.put("skus", List.of(sku));
    Res draft = send("POST", "/product-drafts", md, body, null);
    draft.expect("상품 초안 " + key, 200, 201);
    if (!"VALID".equals(draft.map().get("validationState"))) {
      throw new IllegalStateException("상품 초안 " + key + " 검증 실패: " + Json.write(draft.map().get("problems")));
    }
    String draftId = str(draft.map(), "draftId");
    Res sub = send("POST", "/product-drafts/" + draftId + "/submissions", md, null, null);
    sub.expect("상품 제출 준비 " + key, 200, 201);
    approve(map(sub.map(), "approvalRequest"), "상품 " + key);
    String[] result = new String[2];
    await(
        "상품 " + key + " 반영",
        () -> {
          Res d = send("GET", "/product-drafts/" + draftId, md, null, null);
          if (d.status() != 200) {
            return false;
          }
          Object state = d.map().get("state");
          if ("REJECTED".equals(state)) {
            throw new IllegalStateException("상품 " + key + " 반려: " + Json.write(d.map().get("problems")));
          }
          if (!"APPLIED".equals(state)) {
            return false;
          }
          result[0] = str(d.map(), "resultProductId");
          result[1] = (String) list(d.map(), "resultSkus").stream().findFirst().map(Object::toString).orElse(null);
          return true;
        });
    say("상품 " + key + ": " + result[0] + " " + result[1]);
    return result;
  }

  // ---------- 콘텐츠 ----------

  void content(Map<String, Object> p, String productId) throws Exception {
    String key = str(p, "key");
    if (send("GET", "/product-contents/" + productId, md, null, null).status() == 200) {
      say("콘텐츠 " + key + ": 게시본 있음 → 건너뜀");
      return;
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("sourceSystem", SOURCE);
    body.put("sourceKey", key);
    body.put("productId", productId);
    body.put("name", str(p, "name"));
    body.put("description", str(p, "description"));
    body.put("storageGuide", str(p, "storageGuide"));
    body.put("notices", list(p, "notices"));
    Res draft = send("POST", "/content-drafts", md, body, null);
    draft.expect("콘텐츠 초안 " + key, 200, 201);
    String draftId = str(draft.map(), "draftId");
    upload(draftId, image(str(p, "color"), key), key);
    await(
        "콘텐츠 " + key + " 제출 가능(상품 연결)",
        () -> Boolean.TRUE.equals(send("GET", "/content-drafts/" + draftId, md, null, null).map().get("submittable")));
    Res sub = send("POST", "/content-drafts/" + draftId + "/submissions", md, null, null);
    sub.expect("콘텐츠 제출 준비 " + key, 200, 201);
    approve(map(sub.map(), "approvalRequest"), "콘텐츠 " + key);
    await(
        "콘텐츠 " + key + " 게시",
        () -> send("GET", "/product-contents/" + productId, md, null, null).status() == 200);
    say("콘텐츠 " + key + ": 게시");
  }

  void upload(String draftId, byte[] png, String key) throws Exception {
    String boundary = "----pmart-seed-" + UUID.randomUUID();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    String head =
        "--" + boundary + "\r\nContent-Disposition: form-data; name=\"role\"\r\n\r\nMAIN\r\n"
            + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"sortOrder\"\r\n\r\n0\r\n"
            + "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
            + key + ".png\"\r\nContent-Type: image/png\r\n\r\n";
    out.write(head.getBytes(StandardCharsets.UTF_8));
    out.write(png);
    out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    HttpRequest req =
        HttpRequest.newBuilder(URI.create(api + "/content-drafts/" + draftId + "/images"))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer " + md)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .header("X-Source-System", SOURCE)
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()))
            .build();
    HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    new Res(r.statusCode(), r.body()).expect("대표 이미지 " + key, 200, 201);
  }

  /** 합성 대표 이미지(단색 바탕 + 원 + 시험 표시). 캡처 원본을 쓰지 않는다. */
  static byte[] image(String hex, String key) throws IOException {
    BufferedImage img = new BufferedImage(600, 600, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = img.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    Color base = Color.decode(hex);
    g.setColor(base);
    g.fillRect(0, 0, 600, 600);
    g.setColor(base.brighter());
    g.fillOval(120, 120, 360, 360);
    g.setColor(base.darker());
    g.setStroke(new BasicStroke(8));
    g.drawOval(120, 120, 360, 360);
    g.setColor(Color.WHITE);
    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 56));
    String label = "TEST " + key.replace("SEED-", "");
    int w = g.getFontMetrics().stringWidth(label);
    g.drawString(label, (600 - w) / 2, 320);
    g.dispose();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(img, "png", out);
    return out.toByteArray();
  }

  // ---------- 가격 ----------

  void prices(List<Map<String, Object>> products, Map<String, String[]> ids) throws Exception {
    for (int round = 1; round <= 3; round++) {
      List<Map<String, Object>> missing = new ArrayList<>();
      for (Map<String, Object> p : products) {
        String sku = ids.get(str(p, "key"))[1];
        if (send("GET", "/prices/" + sku, md, null, null).status() != 200) {
          missing.add(p);
        }
      }
      if (missing.isEmpty()) {
        say("가격: 모두 설정됨");
        return;
      }
      List<Object> lines = new ArrayList<>();
      for (Map<String, Object> p : missing) {
        String sku = ids.get(str(p, "key"))[1];
        long amount = num(p, "price");
        lines.add(
            Map.of(
                "lineKey", sku,
                "requestedAmount", amount,
                "attributes", Map.of("sku", sku, "basePrice", amount)));
      }
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("type", "PRICE");
      body.put("targetService", "pricing-service");
      body.put(
          "targetRef",
          "PRICE-SEED-" + DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC).format(Instant.now()));
      body.put("allowedActions", List.of("APPLY_PRICE_VERSION"));
      body.put("summary", "pmart-seed 시험 기본가 " + lines.size() + "건");
      body.put("lines", lines);
      approve(body, "기본가 " + lines.size() + "건");
      try {
        await(
            "기본가 발효",
            () -> {
              for (Map<String, Object> p : missing) {
                if (send("GET", "/prices/" + ids.get(str(p, "key"))[1], md, null, null).status() != 200) {
                  return false;
                }
              }
              return true;
            });
      } catch (IllegalStateException e) {
        // pricing 의 SKU 투영이 늦으면 그 행은 SKU_NOT_FOUND 로 거절된다 → 남은 것만 다시 요청
        say("  가격 일부 미반영 — 다시 요청 (" + round + "/3)");
      }
    }
    throw new IllegalStateException("기본가를 모두 반영하지 못했습니다(가격 승인 결과를 백오피스에서 확인).");
  }

  // ---------- MFC 취급 ----------

  void handling(String mfcId, Map<String, String[]> ids) throws Exception {
    List<Object> items = new ArrayList<>();
    for (String[] id : ids.values()) {
      items.add(Map.of("sku", id[1], "enabled", true));
    }
    Map<String, Object> body = Map.of("reason", "pmart-seed 시험 데이터", "items", items);
    await(
        "MFC 취급 ON (" + mfcId + ")",
        () -> {
          Res r = send("POST", "/mfcs/" + mfcId + "/sku-handling-batches", md, body, null);
          if (r.status() == 422) {
            return false; // SKU 투영 전(SKU_NOT_FOUND) — 잠시 후 다시
          }
          r.expect("MFC 취급 일괄", 200, 201, 202);
          return true;
        });
    say("MFC 취급: " + mfcId + " 에 " + items.size() + "개 ON");
  }

  // ---------- 초기 재고(INITIAL_STOCK 승인 → mfc-inbound 초기 보유 반입 → mfc-inventory 전기, D2) ----------

  /** 시험 SKU 마다 initialStockPerSku 개. 조정(ADJ)으로 넣지 않는다. 반영 확인은 mfc-inbound 반입 결과로. */
  void initialStock(String mfcId, Map<String, String[]> ids) throws Exception {
    long qty = num(data, "initialStockPerSku");
    List<String> skus = new ArrayList<>();
    ids.values().forEach(id -> skus.add(id[1]));
    skus.addAll(extraStockSkus);
    List<Object> lines = new ArrayList<>();
    for (String sku : skus) {
      lines.add(
          Map.of(
              "lineKey", sku,
              "requestedAmount", qty,
              "attributes", Map.of("mfcId", mfcId, "sku", sku, "qty", qty)));
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", "INITIAL_STOCK");
    body.put("targetService", "mfc-inbound-service");
    body.put("targetRef", "INITIAL-" + mfcId + "-" + System.currentTimeMillis());
    body.put("allowedActions", List.of("APPLY_INITIAL_STOCK"));
    body.put("summary", "pmart-seed 시험 초기 재고 " + mfcId + " SKU " + skus.size() + "개 × " + qty);
    body.put("lines", lines);
    String approvalId = approve(body, "초기 재고 " + skus.size() + "건");
    String[] state = new String[1];
    await(
        "초기 재고 반입",
        () -> {
          Res r = send("GET", "/initial-stock-loads?approvalId=" + enc(approvalId), md, null, null);
          List<Map<String, Object>> items = r.status() == 200 ? list(r.map(), "items") : List.of();
          if (items.isEmpty()) {
            return false;
          }
          state[0] = str(items.getFirst(), "state");
          if ("REJECTED".equals(state[0])) {
            throw new IllegalStateException("초기 재고 거절: " + items.getFirst().get("rejectReason"));
          }
          return "APPLIED".equals(state[0]);
        });
    say("초기 재고: " + mfcId + " SKU " + skus.size() + "개 × " + qty + " 반영");
  }

  // ---------- HTTP ----------

  record Res(int status, String body) {
    @SuppressWarnings("unchecked")
    Map<String, Object> map() {
      Object o = body == null || body.isBlank() ? Map.of() : Json.parse(body);
      return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    Res expect(String what, int... ok) {
      for (int s : ok) {
        if (s == status) {
          return this;
        }
      }
      Map<String, Object> m;
      try {
        m = map();
      } catch (RuntimeException e) {
        m = Map.of();
      }
      throw new IllegalStateException(
          what + " 실패: HTTP " + status + " " + m.getOrDefault("code", "") + " " + m.getOrDefault("detail", ""));
    }
  }

  Res send(String method, String path, String token, Object body, Long expectedVersion) throws Exception {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create(api + path))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json, application/problem+json");
    if (token != null) {
      b.header("Authorization", "Bearer " + token);
    }
    if (!"GET".equals(method)) {
      b.header("Idempotency-Key", UUID.randomUUID().toString()).header("X-Source-System", SOURCE);
    }
    if (expectedVersion != null) {
      b.header("X-Expected-Version", String.valueOf(expectedVersion));
    }
    if (body != null) {
      b.header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8));
    } else {
      b.method(method, HttpRequest.BodyPublishers.noBody());
    }
    HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    return new Res(r.statusCode(), r.body());
  }

  interface Check {
    boolean ok() throws Exception;
  }

  /** 승인 반영은 비동기(사건) — 1초 간격, 최대 90초. */
  static void await(String what, Check check) throws Exception {
    Instant until = Instant.now().plusSeconds(90);
    while (Instant.now().isBefore(until)) {
      if (check.ok()) {
        return;
      }
      Thread.sleep(1000);
    }
    throw new IllegalStateException(what + ": 90초 안에 확인되지 않았습니다.");
  }

  // ---------- 보조 ----------

  static String gatewayHost(Path placement) throws IOException {
    String joined = Files.readString(placement, StandardCharsets.UTF_8).replace("\\\r\n", " ").replace("\\\n", " ");
    for (String line : joined.split("\\R")) {
      String t = line.strip();
      if (t.isEmpty() || t.startsWith("#")) {
        continue;
      }
      String[] f = t.split("\\s+");
      for (int i = 3; i < f.length; i++) {
        if (f[i].equals("api-gateway")) {
          return f[1];
        }
      }
    }
    throw new IllegalStateException("placement.conf 에 api-gateway 가 없습니다. --gateway 로 지정하세요.");
  }

  static String readSecret(Path dir, String file, String env, String fallback) throws IOException {
    String v = System.getenv(env);
    if (v != null && !v.isBlank()) {
      return v.strip();
    }
    Path p = dir.resolve(file);
    if (Files.exists(p)) {
      return Files.readString(p, StandardCharsets.UTF_8).strip();
    }
    if (fallback != null) {
      return fallback;
    }
    throw new IllegalStateException(env + " 또는 " + p + " 가 필요합니다.");
  }

  static String randomPassword() {
    byte[] b = new byte[18];
    new SecureRandom().nextBytes(b);
    return "Seed-" + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }

  static void say(String s) {
    System.out.println(s);
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> map(Map<String, Object> m, String k) {
    return (Map<String, Object>) m.get(k);
  }

  @SuppressWarnings("unchecked")
  static <T> List<T> list(Map<String, Object> m, String k) {
    Object v = m.get(k);
    return v == null ? List.of() : (List<T>) v;
  }

  static String str(Map<String, Object> m, String k) {
    Object v = m.get(k);
    return v == null ? null : v.toString();
  }

  static long num(Map<String, Object> m, String k) {
    return ((Number) m.get(k)).longValue();
  }

  static double dbl(Map<String, Object> m, String k) {
    return ((Number) m.get(k)).doubleValue();
  }

  /** 최소 JSON 읽기·쓰기(객체=LinkedHashMap, 배열=ArrayList, 수=Long/Double). */
  static final class Json {
    private final String s;
    private int i;

    private Json(String s) {
      this.s = s;
    }

    static Object parse(String s) {
      Json p = new Json(s);
      Object v = p.value();
      p.ws();
      if (p.i != s.length()) {
        throw new IllegalArgumentException("JSON 뒤에 남은 문자 @" + p.i);
      }
      return v;
    }

    private void ws() {
      while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
        i++;
      }
    }

    private Object value() {
      ws();
      char c = s.charAt(i);
      switch (c) {
        case '{' -> {
          i++;
          Map<String, Object> m = new LinkedHashMap<>();
          ws();
          if (s.charAt(i) == '}') {
            i++;
            return m;
          }
          while (true) {
            ws();
            String k = string();
            ws();
            expect(':');
            m.put(k, value());
            ws();
            if (s.charAt(i) == ',') {
              i++;
            } else {
              expect('}');
              return m;
            }
          }
        }
        case '[' -> {
          i++;
          List<Object> l = new ArrayList<>();
          ws();
          if (s.charAt(i) == ']') {
            i++;
            return l;
          }
          while (true) {
            l.add(value());
            ws();
            if (s.charAt(i) == ',') {
              i++;
            } else {
              expect(']');
              return l;
            }
          }
        }
        case '"' -> {
          return string();
        }
        default -> {
          if (s.startsWith("true", i)) {
            i += 4;
            return Boolean.TRUE;
          }
          if (s.startsWith("false", i)) {
            i += 5;
            return Boolean.FALSE;
          }
          if (s.startsWith("null", i)) {
            i += 4;
            return null;
          }
          int start = i;
          while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
            i++;
          }
          String n = s.substring(start, i);
          if (n.isEmpty()) {
            throw new IllegalArgumentException("JSON 값 오류 @" + start);
          }
          return n.contains(".") || n.contains("e") || n.contains("E") ? (Object) Double.parseDouble(n) : (Object) Long.parseLong(n);
        }
      }
    }

    private void expect(char c) {
      if (s.charAt(i) != c) {
        throw new IllegalArgumentException("JSON '" + c + "' 기대 @" + i);
      }
      i++;
    }

    private String string() {
      expect('"');
      StringBuilder b = new StringBuilder();
      while (true) {
        char c = s.charAt(i++);
        if (c == '"') {
          return b.toString();
        }
        if (c == '\\') {
          char e = s.charAt(i++);
          switch (e) {
            case 'n' -> b.append('\n');
            case 't' -> b.append('\t');
            case 'r' -> b.append('\r');
            case 'b' -> b.append('\b');
            case 'f' -> b.append('\f');
            case 'u' -> {
              b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
              i += 4;
            }
            default -> b.append(e);
          }
        } else {
          b.append(c);
        }
      }
    }

    static String write(Object o) {
      StringBuilder b = new StringBuilder();
      write(o, b);
      return b.toString();
    }

    private static void write(Object o, StringBuilder b) {
      if (o == null) {
        b.append("null");
      } else if (o instanceof String str) {
        b.append('"');
        for (char c : str.toCharArray()) {
          switch (c) {
            case '"' -> b.append("\\\"");
            case '\\' -> b.append("\\\\");
            case '\n' -> b.append("\\n");
            case '\r' -> b.append("\\r");
            case '\t' -> b.append("\\t");
            default -> {
              if (c < 0x20) {
                b.append(String.format("\\u%04x", (int) c));
              } else {
                b.append(c);
              }
            }
          }
        }
        b.append('"');
      } else if (o instanceof Number || o instanceof Boolean) {
        b.append(o);
      } else if (o instanceof Map<?, ?> m) {
        b.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> e : m.entrySet()) {
          if (!first) {
            b.append(',');
          }
          first = false;
          write(String.valueOf(e.getKey()), b);
          b.append(':');
          write(e.getValue(), b);
        }
        b.append('}');
      } else if (o instanceof List<?> l) {
        b.append('[');
        for (int k = 0; k < l.size(); k++) {
          if (k > 0) {
            b.append(',');
          }
          write(l.get(k), b);
        }
        b.append(']');
      } else {
        write(o.toString(), b);
      }
    }
  }
}
