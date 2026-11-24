package com.pmart.promotion.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 진열 테마 사본(0.3.0 V5 — storefront {@code /internal/storefront/themes}). 기간 할인 대상 THEME 은 이 사본의 상품으로
 * 판정한다. 통째로 바꾼다(테마가 진열에서 빠지면 그 행사의 할인도 멈춘다).
 */
@Repository
public class ThemeCopyStore {

  public record Theme(String sectionId, String kind, String title, List<String> productIds) {}

  public record ThemeSummary(String sectionId, String kind, String title, int productCount) {}

  public record State(int layoutVersion, Instant syncedAt) {}

  private final JdbcTemplate jdbc;

  public ThemeCopyStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Optional<State> state() {
    return jdbc
        .query(
            "SELECT layout_version, synced_at FROM theme_copy_state WHERE id = 1",
            (rs, i) -> new State(rs.getInt(1), rs.getTimestamp(2).toInstant()))
        .stream()
        .findFirst();
  }

  /** 같은 진열 버전이면 받은 시각만, 다르면 사본을 통째로 바꾼다(호출자 트랜잭션). 바꿨으면 true. */
  public boolean replace(int layoutVersion, List<Theme> themes, Instant at) {
    Integer current =
        jdbc
            .query(
                "SELECT layout_version FROM theme_copy_state WHERE id = 1 FOR UPDATE",
                (rs, i) -> rs.getInt(1))
            .stream()
            .findFirst()
            .orElse(null);
    if (current != null && current == layoutVersion) {
      jdbc.update("UPDATE theme_copy_state SET synced_at = ? WHERE id = 1", Timestamp.from(at));
      return false;
    }
    jdbc.update("DELETE FROM theme_copy_product");
    jdbc.update("DELETE FROM theme_copy");
    for (Theme t : themes) {
      jdbc.update(
          "INSERT INTO theme_copy (section_id, kind, title) VALUES (?,?,?) ON CONFLICT DO NOTHING",
          t.sectionId(),
          t.kind(),
          t.title());
      for (String p : t.productIds()) {
        jdbc.update(
            "INSERT INTO theme_copy_product (section_id, product_id) VALUES (?,?)"
                + " ON CONFLICT DO NOTHING",
            t.sectionId(),
            p);
      }
    }
    jdbc.update(
        "INSERT INTO theme_copy_state (id, layout_version, synced_at) VALUES (1, ?, ?)"
            + " ON CONFLICT (id) DO UPDATE SET layout_version = EXCLUDED.layout_version,"
            + " synced_at = EXCLUDED.synced_at",
        layoutVersion,
        Timestamp.from(at));
    return true;
  }

  /** sectionId → kind(검사용). */
  public Map<String, String> kinds(Collection<String> sectionIds) {
    Map<String, String> out = new HashMap<>();
    if (sectionIds.isEmpty()) {
      return out;
    }
    jdbc.query(
        "SELECT section_id, kind FROM theme_copy WHERE section_id = ANY (?::varchar[])",
        rs -> {
          out.put(rs.getString(1), rs.getString(2));
        },
        "{" + String.join(",", sectionIds) + "}");
    return out;
  }

  /** sectionId → 이름(행사 대상 표시용). */
  public Map<String, String> titles(Collection<String> sectionIds) {
    Map<String, String> out = new HashMap<>();
    if (sectionIds.isEmpty()) {
      return out;
    }
    jdbc.query(
        "SELECT section_id, title FROM theme_copy WHERE section_id = ANY (?::varchar[])",
        rs -> {
          out.put(rs.getString(1), rs.getString(2));
        },
        "{" + String.join(",", sectionIds) + "}");
    return out;
  }

  public List<ThemeSummary> all() {
    return jdbc.query(
        "SELECT t.section_id, t.kind, t.title, (SELECT count(*) FROM theme_copy_product p"
            + " WHERE p.section_id = t.section_id) AS n FROM theme_copy t ORDER BY t.title, t.section_id",
        (rs, i) ->
            new ThemeSummary(
                rs.getString("section_id"),
                rs.getString("kind"),
                rs.getString("title"),
                rs.getInt("n")));
  }
}
