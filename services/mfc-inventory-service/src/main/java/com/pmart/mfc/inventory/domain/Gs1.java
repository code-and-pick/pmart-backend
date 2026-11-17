package com.pmart.mfc.inventory.domain;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * GS1-128 LOT 바코드(2026-11-17, 요청 IV-4 — 사용자 결정): (01) GTIN-14 + (17) 소비기한 YYMMDD + (10) LOT 번호.
 *
 * <p>읽기는 두 모양을 받는다 — 스캐너 원문(앞의 {@code ]C1} 심볼 식별자는 떼고, 가변 길이 뒤는 그룹 구분자 GS {@code \u001d})과 사람이 읽는 꼴
 * {@code (01)…(17)…(10)…}. AI 는 01·10·11·15·17·21 만 안다(그 밖은 틀린 값). 만들 때는 고정 길이(01·17)를 앞에, 가변(10)을
 * 끝에 둔다 — FNC1 구분자가 필요 없다.
 */
public final class Gs1 {

  public static final char GS = '\u001d';
  private static final int MAX_LOT = 20;
  private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");
  private static final Pattern HRI = Pattern.compile("\\((\\d{2})\\)([^()]*)");

  /** 읽은 값. 없는 AI 는 null. */
  public record Parsed(@Nullable String gtin, @Nullable String lot, @Nullable LocalDate expiry) {}

  /** 틀린 GS1 값. */
  public static final class Invalid extends RuntimeException {
    public Invalid(String message) {
      super(message);
    }
  }

  private Gs1() {}

  /** 사람이 읽는 꼴 {@code (01)…(17)…(10)…} — GTIN 이 없으면 null(라벨을 만들 수 없다). */
  public static @Nullable String humanReadable(
      @Nullable String gtin14, String lotNo, @Nullable LocalDate expiry) {
    if (gtin14 == null) {
      return null;
    }
    return "(01)"
        + gtin14
        + (expiry == null ? "" : "(17)" + YYMMDD.format(expiry))
        + "(10)"
        + lotNo;
  }

  /** 상품 바코드(EAN-8·UPC-12·EAN-13·GTIN-14, 선행 0 보존)를 GTIN-14 로. 숫자가 아니거나 길이가 다르면 null. */
  public static @Nullable String gtin14(String barcode) {
    String b = barcode.trim();
    if (!b.matches("\\d{8}|\\d{12,14}")) {
      return null;
    }
    return "0".repeat(14 - b.length()) + b;
  }

  public static Parsed parse(String raw) {
    String s = raw.strip();
    if (s.startsWith("]C1") || s.startsWith("]c1")) {
      s = s.substring(3);
    }
    if (s.isEmpty()) {
      throw new Invalid("GS1 값이 비었습니다.");
    }
    return s.startsWith("(") ? parseHri(s) : parseRaw(s);
  }

  private static Parsed parseHri(String s) {
    Matcher m = HRI.matcher(s);
    Fields f = new Fields();
    int end = 0;
    while (m.find()) {
      if (m.start() != end) {
        throw new Invalid("GS1 괄호 꼴이 아닙니다: " + s);
      }
      f.put(m.group(1), m.group(2));
      end = m.end();
    }
    if (end != s.length()) {
      throw new Invalid("GS1 괄호 꼴이 아닙니다: " + s);
    }
    return f.done();
  }

  private static Parsed parseRaw(String s) {
    Fields f = new Fields();
    int i = 0;
    while (i < s.length()) {
      if (s.charAt(i) == GS) {
        i++;
        continue;
      }
      if (i + 2 > s.length()) {
        throw new Invalid("GS1 AI 가 잘렸습니다: " + s);
      }
      String ai = s.substring(i, i + 2);
      i += 2;
      int fixed = fixedLength(ai);
      String value;
      if (fixed > 0) {
        if (i + fixed > s.length()) {
          throw new Invalid("GS1 (" + ai + ") 길이가 모자랍니다: " + s);
        }
        value = s.substring(i, i + fixed);
        i += fixed;
      } else {
        int gs = s.indexOf(GS, i);
        int stop = gs < 0 ? s.length() : gs;
        value = s.substring(i, stop);
        i = stop;
      }
      f.put(ai, value);
    }
    return f.done();
  }

  private static int fixedLength(String ai) {
    return switch (ai) {
      case "01" -> 14;
      case "11", "15", "17" -> 6;
      case "10", "21" -> 0;
      default -> throw new Invalid("모르는 GS1 AI (" + ai + ")");
    };
  }

  private static final class Fields {
    private @Nullable String gtin;
    private @Nullable String lot;
    private @Nullable LocalDate expiry;

    void put(String ai, String value) {
      switch (ai) {
        case "01" -> {
          if (!value.matches("\\d{14}")) {
            throw new Invalid("GS1 (01) 은 숫자 14자리입니다: " + value);
          }
          gtin = value;
        }
        case "10" -> {
          if (value.isEmpty() || value.length() > MAX_LOT) {
            throw new Invalid("GS1 (10) 은 1~20자입니다: " + value);
          }
          lot = value;
        }
        case "17" -> expiry = date(value);
        case "11", "15" -> date(value); // 제조일·상미기한 — 읽기만(판정은 LOT)
        case "21" -> {
          // 일련번호 — 쓰지 않음
        }
        default -> throw new Invalid("모르는 GS1 AI (" + ai + ")");
      }
    }

    Parsed done() {
      return new Parsed(gtin, lot, expiry);
    }
  }

  /** YYMMDD — 연도는 2000년대, 일 00 은 그 달 마지막 날(GS1 규칙). */
  static LocalDate date(String v) {
    if (!v.matches("\\d{6}")) {
      throw new Invalid("GS1 날짜는 YYMMDD 입니다: " + v);
    }
    int y = 2000 + Integer.parseInt(v.substring(0, 2));
    int mo = Integer.parseInt(v.substring(2, 4));
    int d = Integer.parseInt(v.substring(4, 6));
    try {
      YearMonth ym = YearMonth.of(y, mo);
      return d == 0 ? ym.atEndOfMonth() : ym.atDay(d);
    } catch (java.time.DateTimeException e) {
      throw new Invalid("GS1 날짜가 틀렸습니다: " + v);
    }
  }
}
