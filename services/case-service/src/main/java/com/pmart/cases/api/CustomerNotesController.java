package com.pmart.cases.api;

import com.pmart.cases.api.generated.CustomerNotesApi;
import com.pmart.cases.api.generated.model.CreateCustomerNoteRequest;
import com.pmart.cases.api.generated.model.CustomerNote;
import com.pmart.cases.api.generated.model.ListCases200ResponsePage;
import com.pmart.cases.api.generated.model.ListCustomerNotes200Response;
import com.pmart.cases.api.generated.model.ReviseCustomerNoteRequest;
import com.pmart.cases.application.CustomerNoteService;
import com.pmart.cases.infrastructure.CustomerNoteStore.NoteRow;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 CustomerNotesApi — 고객 상담 메모(0.34.0 R-13). 규칙은 {@link CustomerNoteService}. */
@RestController
public class CustomerNotesController implements CustomerNotesApi {

  private final CustomerNoteService notes;

  public CustomerNotesController(CustomerNoteService notes) {
    this.notes = notes;
  }

  @Override
  public ResponseEntity<CustomerNote> createCustomerNote(
      String idempotencyKey, String xSourceSystem, CreateCustomerNoteRequest body) {
    NoteRow n = notes.add(idempotencyKey, xSourceSystem, body.getCustomerId(), body.getText());
    return ResponseEntity.created(URI.create("/api/v1/customer-notes/" + n.noteId())).body(out(n));
  }

  @Override
  public ResponseEntity<ListCustomerNotes200Response> listCustomerNotes(
      String customerId, Integer size, @Nullable String cursor) {
    var page = notes.list(customerId, cursor, size == null ? 20 : size);
    var p = new ListCases200ResponsePage();
    p.setNextCursor(page.nextCursor());
    p.setSize(page.items().size());
    return ResponseEntity.ok(
        new ListCustomerNotes200Response()
            .items(page.items().stream().map(CustomerNotesController::out).toList())
            .page(p));
  }

  @Override
  public ResponseEntity<CustomerNote> reviseCustomerNote(
      String noteId, String idempotencyKey, String xSourceSystem, ReviseCustomerNoteRequest body) {
    return ResponseEntity.ok(
        out(notes.revise(idempotencyKey, xSourceSystem, noteId, body.getText())));
  }

  @Override
  public ResponseEntity<CustomerNote> deleteCustomerNote(
      String noteId, String idempotencyKey, String xSourceSystem) {
    return ResponseEntity.ok(out(notes.delete(idempotencyKey, xSourceSystem, noteId)));
  }

  private static CustomerNote out(NoteRow n) {
    return new CustomerNote()
        .noteId(n.noteId())
        .customerId(n.customerId())
        .text(n.text())
        .authorId(n.authorId())
        .createdAt(odt(n.createdAt()))
        .editedAt(odt(n.editedAt()))
        .deleted(n.deletedAt() != null)
        .deletedAt(odt(n.deletedAt()));
  }

  private static @Nullable OffsetDateTime odt(@Nullable Instant i) {
    return i == null ? null : i.atOffset(ZoneOffset.UTC);
  }
}
