package com.pmart.cases.api;

import com.pmart.cases.api.generated.InquiryPhotosApi;
import com.pmart.cases.api.generated.model.InquiryPhoto;
import com.pmart.cases.application.InquiryPhotoService;
import com.pmart.cases.infrastructure.InquiryPhotoStore.PhotoRow;
import com.pmart.common.web.ErrorCode;
import com.pmart.common.web.PmartException;
import java.io.IOException;
import java.time.Duration;
import java.time.ZoneOffset;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 계약 InquiryPhotosApi(0.14.0) — 문의 사진 올리기·본문. 규칙은 {@link InquiryPhotoService}. */
@RestController
public class InquiryPhotosController implements InquiryPhotosApi {

  private final InquiryPhotoService photos;

  public InquiryPhotosController(InquiryPhotoService photos) {
    this.photos = photos;
  }

  @Override
  public ResponseEntity<InquiryPhoto> uploadInquiryPhoto(
      String idempotencyKey, String xSourceSystem, MultipartFile file) {
    byte[] bytes;
    try {
      bytes = file.getBytes();
    } catch (IOException e) {
      throw new PmartException(ErrorCode.VALIDATION_FAILED, "사진을 읽을 수 없습니다.");
    }
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(photo(photos.upload(idempotencyKey, xSourceSystem, bytes)));
  }

  @Override
  public ResponseEntity<Resource> getInquiryPhoto(String photoId) {
    var p = photos.body(photoId);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(p.row().contentType()))
        .contentLength(p.row().sizeBytes())
        .eTag("\"" + p.row().sha256() + "\"")
        // 본인·담당 직원만 보는 사진 — 공유 캐시 금지
        .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
        .body(new InputStreamResource(p.body()));
  }

  static InquiryPhoto photo(PhotoRow r) {
    var p = new InquiryPhoto();
    p.setPhotoId(r.photoId());
    p.setUrl("/api/v1/inquiry-photos/" + r.photoId());
    p.setContentType(InquiryPhoto.ContentTypeEnum.fromValue(r.contentType()));
    p.setSizeBytes(r.sizeBytes());
    p.setUploadedAt(r.uploadedAt().atOffset(ZoneOffset.UTC));
    return p;
  }
}
