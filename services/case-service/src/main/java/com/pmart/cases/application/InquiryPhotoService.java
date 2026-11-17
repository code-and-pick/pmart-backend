package com.pmart.cases.application;

import com.pmart.cases.domain.CsIntakeRules;
import com.pmart.cases.domain.InquiryRules;
import com.pmart.cases.infrastructure.InquiryPhotoStore;
import com.pmart.cases.infrastructure.InquiryPhotoStore.PhotoRow;
import com.pmart.cases.infrastructure.InquiryStore;
import com.pmart.cases.infrastructure.InquiryStore.InquiryRow;
import com.pmart.common.messaging.RequestIdempotency;
import com.pmart.common.messaging.RequestIdempotency.RequestKey;
import com.pmart.common.security.PmartPrincipal;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 문의 사진(0.14.0 — 2026-11-17 사용자 "리뷰 방식 그대로", 업무 원문 Commerce §14.1). 문의를 남긴 사람(고객·라이더)이 먼저 올리고, 문의를
 * 남길 때(photoIds) 또는 남긴 뒤 보완(addInquiryPhotos)으로 붙인다. 본문은 본인과 그 문의를 볼 수 있는 직원만. 하루 지나도 붙지 않은 사진은
 * 지운다. 사진 내용·파일 이름은 로그에 남기지 않는다.
 */
@Service
public class InquiryPhotoService {

  public record Photo(PhotoRow row, InputStream body) {}

  private final InquiryPhotoStore store;
  private final InquiryStore inquiries;
  private final InquiryService inquiryService;
  private final InquiryPhotoStorage storage;
  private final RequestIdempotency idempotency;
  private final Clock clock;

  public InquiryPhotoService(
      InquiryPhotoStore store,
      InquiryStore inquiries,
      InquiryService inquiryService,
      InquiryPhotoStorage storage,
      RequestIdempotency idempotency,
      Clock clock) {
    this.store = store;
    this.inquiries = inquiries;
    this.inquiryService = inquiryService;
    this.storage = storage;
    this.idempotency = idempotency;
    this.clock = clock;
  }

  /** 사진 한 장 올리기(문의를 남기는 고객·라이더만). 같은 Idempotency-Key·같은 본문이면 같은 사진. */
  public PhotoRow upload(String requestId, String sourceSystem, byte[] bytes) {
    PmartPrincipal p = requester();
    InquiryRules.photoProblem(bytes)
        .ifPresent(
            m -> {
              throw new PmartException(
                  "PHOTO_INVALID", HttpStatus.UNPROCESSABLE_CONTENT, m, false, List.of());
            });
    String type = InquiryRules.photoType(bytes).orElseThrow();
    String sha = sha256(bytes);
    String photoId =
        idempotency
            .execute(
                new RequestKey(sourceSystem, requestId, "uploadInquiryPhoto", p.subject()),
                Map.of("sha256", sha, "size", bytes.length),
                String.class,
                () -> {
                  String id = "IPH-" + UUID.randomUUID();
                  String key = "inquiry-photos/" + p.subject() + "/" + id;
                  storage.put(key, bytes, type); // 실패하면 행도 남지 않는다
                  store.insert(
                      new PhotoRow(id, p.subject(), key, type, bytes.length, sha, now(), null));
                  return id;
                })
            .value();
    return store.find(photoId).orElseThrow();
  }

  /**
   * 남긴 문의에 사진 보완(원문 "접수는 됐지만 첨부가 실패하면 원접수에 보완"). 남긴 본인만, 닫힌 문의는 409 INQUIRY_CLOSED, 합쳐 5장까지. 상태·버전은
   * 바꾸지 않는다(답변 여부와 무관).
   */
  public InquiryService.View add(
      String requestId, String sourceSystem, String inquiryId, List<String> photoIds) {
    PmartPrincipal p = requester();
    if (photoIds == null || photoIds.isEmpty()) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "붙일 사진(photoIds)이 필요합니다.");
    }
    idempotency.execute(
        new RequestKey(sourceSystem, requestId, "addInquiryPhotos:" + inquiryId, p.subject()),
        photoIds,
        String.class,
        () -> {
          InquiryRow row =
              inquiries
                  .lock(inquiryId)
                  .filter(r -> mine(p, r))
                  .orElseThrow(
                      () ->
                          PmartException.notFound(
                              "INQUIRY_NOT_FOUND", "문의를 찾을 수 없습니다: " + inquiryId));
          if (InquiryRules.CLOSED.equals(row.status())) {
            throw new PmartException(
                "INQUIRY_CLOSED", HttpStatus.CONFLICT, "닫힌 문의입니다.", false, List.of());
          }
          int already = store.ofInquiry(inquiryId).size();
          InquiryRules.photoIdsProblem(photoIds, already)
              .ifPresent(
                  m -> {
                    throw new PmartException(ErrorCode.VALIDATION_FAILED, m);
                  });
          inquiryService.attach(inquiryId, p.subject(), photoIds, already);
          return inquiryId;
        });
    return inquiryService.get(inquiryId);
  }

  /** 본문 — 붙은 사진은 그 문의를 볼 수 있는 사람(본인·담당 직원), 아직 안 붙은 사진은 올린 본인만. 아니면 404. */
  public Photo body(String photoId) {
    PmartPrincipal p =
        PmartPrincipal.current()
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "토큰이 필요합니다."));
    PhotoRow row = store.find(photoId).orElseThrow(() -> notFound(photoId));
    if (row.inquiryId() == null) {
      if (!row.ownerId().equals(p.subject()) || p.type() == PmartPrincipal.PrincipalType.OPERATOR) {
        throw notFound(photoId);
      }
    } else {
      try {
        inquiryService.get(row.inquiryId()); // 볼 수 없으면 404·403
      } catch (PmartException e) {
        throw e.status() == HttpStatus.FORBIDDEN ? e : notFound(photoId);
      }
    }
    return new Photo(row, storage.get(row.objectKey()));
  }

  /** 붙지 않은 지 하루 지난 사진 정리. */
  public int sweepOrphans() {
    int n = 0;
    for (PhotoRow r : store.orphans(now().minus(InquiryRules.ORPHAN_PHOTO_TTL), 100)) {
      if (storage.delete(r.objectKey())) {
        store.delete(r.photoId());
        n++;
      }
    }
    return n;
  }

  private static boolean mine(PmartPrincipal p, InquiryRow r) {
    String type =
        p.type() == PmartPrincipal.PrincipalType.RIDER ? InquiryRules.RIDER : InquiryRules.CUSTOMER;
    return r.customerId().equals(p.subject())
        && type.equals(r.requesterType())
        && !CsIntakeRules.ORIGIN_STAFF_INTAKE.equals(r.origin());
  }

  private static PmartPrincipal requester() {
    PmartPrincipal p =
        PmartPrincipal.current()
            .orElseThrow(() -> new PmartException(ErrorCode.FORBIDDEN, "토큰이 필요합니다."));
    if (p.type() != PmartPrincipal.PrincipalType.CUSTOMER
        && p.type() != PmartPrincipal.PrincipalType.RIDER) {
      throw new PmartException(ErrorCode.FORBIDDEN, "문의 사진은 문의를 남기는 회원·라이더만 올립니다.");
    }
    return p;
  }

  private static PmartException notFound(String photoId) {
    return PmartException.notFound("PHOTO_NOT_FOUND", "사진이 없습니다: " + photoId);
  }

  private Instant now() {
    return clock.instant().truncatedTo(ChronoUnit.MICROS);
  }

  private static String sha256(byte[] b) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
