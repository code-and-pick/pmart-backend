package com.pmart.delivery.execution.api;

import com.pmart.delivery.execution.api.generated.MeApi;
import com.pmart.delivery.execution.api.generated.model.ExecutionState;
import com.pmart.delivery.execution.api.generated.model.ListMyDeliveries200Response;
import com.pmart.delivery.execution.api.generated.model.ListMyDeliveries200ResponsePage;
import com.pmart.delivery.execution.application.ExecutionService;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 MeApi — 내 배송(0.2.0). 수행료·정산은 아직 501(인터페이스 default). */
@RestController
public class MeController implements MeApi {

  private final ExecutionService service;
  private final ExecutionViews views;

  public MeController(ExecutionService service, ExecutionViews views) {
    this.service = service;
    this.views = views;
  }

  @Override
  public ResponseEntity<ListMyDeliveries200Response> listMyDeliveries(
      @Nullable LocalDate date,
      @Nullable ExecutionState state,
      Integer size,
      @Nullable String cursor) {
    var items = service.mine(date, state == null ? null : state.getValue(), size + 1, cursor);
    boolean more = items.size() > size;
    var page = more ? items.subList(0, size) : items;
    int offset = cursor == null || cursor.isBlank() ? 0 : Integer.parseInt(cursor);
    return ResponseEntity.ok(
        new ListMyDeliveries200Response()
            .items(page.stream().map(views::delivery).toList())
            .page(
                new ListMyDeliveries200ResponsePage()
                    .nextCursor(more ? String.valueOf(offset + size) : null)
                    .size(page.size())));
  }
}
