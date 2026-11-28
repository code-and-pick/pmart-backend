import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.cloud.context.encrypt.EncryptorFactory;
import org.springframework.security.crypto.encrypt.TextEncryptor;

/**
 * {cipher} 값의 지문만 낸다 — 평문은 출력하지 않는다(verify-cipher.sh 가 쓴다).
 * config-server 와 같은 {@link EncryptorFactory}(ConfigCipherTool 과 같음)로 복호화한다.
 *
 * <p>키·salt: 환경변수 PMART_ENC_KEY·PMART_ENC_SALT(앞뒤 공백 제거 — ConfigCipherTool 과 같음).
 * <br>fp  모드(기본): 표준입력 "id TAB 암호문" → 표준출력 "id TAB 길이 TAB sha256(평문) TAB sha256(평문 끝 공백 제거)" 또는 "id TAB ERR".
 * <br>enc 모드(시험 전용): 표준입력 "id TAB 평문" → "id TAB 암호문".
 */
public final class CipherFingerprint {
  public static void main(String[] args) throws Exception {
    String key = System.getenv("PMART_ENC_KEY");
    String salt = System.getenv("PMART_ENC_SALT");
    if (key == null || salt == null || key.isBlank() || salt.isBlank()) {
      System.err.println("PMART_ENC_KEY·PMART_ENC_SALT 가 비어 있다");
      System.exit(2);
    }
    TextEncryptor enc = new EncryptorFactory(salt.strip()).create(key.strip());
    boolean encMode = args.length > 0 && args[0].equals("enc");
    // b64 모드(migrate-cipher.sh 전용): "id TAB base64(평문)" — 평문이 나가므로 터미널로는 내지 않는다(파이프로만)
    boolean b64Mode = args.length > 0 && args[0].equals("b64");
    if (b64Mode && !"pipe".equals(System.getenv("PMART_B64_OUT"))) {
      System.err.println("b64 모드는 migrate-cipher.sh 가 파이프로만 쓴다(PMART_B64_OUT=pipe 없이는 거부)");
      System.exit(2);
    }
    BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
    for (String line; (line = in.readLine()) != null; ) {
      if (line.isBlank()) continue;
      int t = line.indexOf('\t');
      String id = line.substring(0, t);
      String v = line.substring(t + 1);
      if (encMode) {
        System.out.println(id + "\t" + enc.encrypt(v));
        continue;
      }
      try {
        String plain = enc.decrypt(v);
        if (b64Mode) {
          System.out.println(id + "\t" + java.util.Base64.getEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8)));
          continue;
        }
        System.out.println(id + "\t" + plain.getBytes(StandardCharsets.UTF_8).length + "\t" + sha(plain) + "\t" + sha(plain.stripTrailing()));
      } catch (RuntimeException e) {
        System.out.println(id + "\tERR");
      }
    }
  }

  private static String sha(String s) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
  }
}
