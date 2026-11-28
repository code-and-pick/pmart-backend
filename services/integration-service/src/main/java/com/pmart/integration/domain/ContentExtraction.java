package com.pmart.integration.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.jspecify.annotations.Nullable;

/**
 * 파일 본문에서 청킹 서비스로 넘길 내용 추출: BOM 제거, 줄바꿈 LF, 앞뒤 빈 줄 정리, 첫 줄 {@code # 제목}. 마크다운 구조(제목·표·목록)는 그대로 둔다 —
 * 나누기는 청킹 서비스가 한다.
 */
public final class ContentExtraction {

  private static final char BOM = (char) 0xFEFF;

  private ContentExtraction() {}

  public static String normalize(String raw) {
    String s = !raw.isEmpty() && raw.charAt(0) == BOM ? raw.substring(1) : raw;
    s = s.replace("\r\n", "\n").replace('\r', '\n');
    return s.strip() + "\n";
  }

  /** 첫 글 줄이 {@code # 제목} 이면 제목, 아니면 null. */
  public static @Nullable String title(String content) {
    String first = content.lines().filter(l -> !l.isBlank()).findFirst().orElse("").strip();
    if (!first.startsWith("# ")) {
      return null;
    }
    String t = first.substring(2).strip();
    return t.isEmpty() ? null : t;
  }

  public static String sha256(String content) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(content.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
