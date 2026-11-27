package com.pmart.cdc.outbound.api;

import com.pmart.cdc.outbound.api.generated.model.DepartureValidation;
import com.pmart.cdc.outbound.api.generated.model.ListCenterDepartures200Response;
import com.pmart.cdc.outbound.api.generated.model.ListCenterLpns200Response;
import com.pmart.cdc.outbound.api.generated.model.ListCenterShortages200Response;
import com.pmart.cdc.outbound.api.generated.model.ListCenterTransfers200Response;
import com.pmart.cdc.outbound.api.generated.model.ListCenterWaves200Response;
import com.pmart.cdc.outbound.api.generated.model.ListTasks200Response;
import com.pmart.cdc.outbound.api.generated.model.Task;
import com.pmart.cdc.outbound.api.generated.model.TransferDetail;
import com.pmart.cdc.outbound.api.generated.model.TransferIntake;
import com.pmart.cdc.outbound.api.generated.model.TransferSummary;
import com.pmart.cdc.outbound.api.generated.model.Wave;
import com.pmart.cdc.outbound.infrastructure.DisplayNumberStore;
import com.pmart.cdc.outbound.infrastructure.DisplayNumberStore.Kind;
import com.pmart.cdc.outbound.infrastructure.ProductNames;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 화면 번호·상품 이름 붙이기(0.21.0 — PC5 요청, ADR-0070 덧붙임). 컨트롤러가 만든 응답(Task·Wave·TRF·출발 검증과 그 목록)에 내보내기 직전 한
 * 곳에서 붙인다. 붙이다 실패하면 로그만 남기고 원래 응답 그대로(번호·이름은 화면 보조값).
 */
@ControllerAdvice(basePackageClasses = Labels.class)
class Labels implements ResponseBodyAdvice<Object> {

  private static final Logger log = LoggerFactory.getLogger(Labels.class);

  private final DisplayNumberStore numbers;
  private final ProductNames names;
  private final com.pmart.cdc.outbound.application.WaveAdjustments adjustments;

  Labels(
      DisplayNumberStore numbers,
      ProductNames names,
      com.pmart.cdc.outbound.application.WaveAdjustments adjustments) {
    this.numbers = numbers;
    this.names = names;
    this.adjustments = adjustments;
  }

  @Override
  public boolean supports(
      MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
    return true;
  }

  @Override
  public @Nullable Object beforeBodyWrite(
      @Nullable Object body,
      MethodParameter returnType,
      MediaType selectedContentType,
      Class<? extends HttpMessageConverter<?>> selectedConverterType,
      ServerHttpRequest request,
      ServerHttpResponse response) {
    try {
      switch (body) {
        case Task t -> tasks(List.of(t));
        case ListTasks200Response l -> tasks(l.getItems());
        case Wave w -> waves(List.of(w));
        case ListCenterWaves200Response l -> waves(l.getItems());
        case TransferSummary s -> summaries(List.of(s));
        case ListCenterTransfers200Response l -> summaries(l.getItems());
        case TransferDetail d -> detail(d);
        case TransferIntake i -> {
          i.setTrfNumber(get(numbers.numbers(Kind.TRF, list(i.getTrfId())), i.getTrfId()));
          i.setWaveNumber(get(numbers.numbers(Kind.WAVE, list(i.getWaveId())), i.getWaveId()));
        }
        case DepartureValidation d -> departures(List.of(d));
        case ListCenterDepartures200Response l -> departures(l.getItems());
        case ListCenterShortages200Response l -> shortages(l);
        case ListCenterLpns200Response l -> lpns(l);
        case null, default -> {}
      }
    } catch (RuntimeException e) {
      log.warn("화면 번호·상품 이름 붙이기 실패(원래 응답 그대로): {}", e.toString());
    }
    return body;
  }

  void tasks(@Nullable List<Task> ts) {
    if (ts == null || ts.isEmpty()) {
      return;
    }
    Set<String> taskIds = new HashSet<>();
    Set<String> waveIds = new HashSet<>();
    Set<String> trfIds = new HashSet<>();
    Set<String> skus = new HashSet<>();
    for (Task t : ts) {
      add(taskIds, t.getTaskId());
      add(waveIds, t.getWaveId());
      add(trfIds, t.getTrfId());
      if (t.getTrfIds() != null) {
        trfIds.addAll(t.getTrfIds());
      }
      for (var l :
          t.getLines() == null
              ? List.<com.pmart.cdc.outbound.api.generated.model.TaskLinesInner>of()
              : t.getLines()) {
        add(skus, l.getSku());
        if (l.getShares() != null) {
          l.getShares().forEach(s -> add(trfIds, s.getTrfId()));
        }
      }
    }
    Map<String, String> k = numbers.numbers(Kind.TASK, taskIds);
    Map<String, String> w = numbers.numbers(Kind.WAVE, waveIds);
    Map<String, String> tr = numbers.numbers(Kind.TRF, trfIds);
    Map<String, String> n = names.of(skus);
    for (Task t : ts) {
      t.setTaskNumber(get(k, t.getTaskId()));
      t.setWaveNumber(get(w, t.getWaveId()));
      t.setTrfNumber(get(tr, t.getTrfId()));
      if (t.getTrfIds() != null) {
        t.setTrfNumbers(
            new ArrayList<>(t.getTrfIds().stream().map(id -> tr.getOrDefault(id, "")).toList()));
      }
      if (t.getLines() != null) {
        for (var l : t.getLines()) {
          l.setProductName(get(n, l.getSku()));
          if (l.getShares() != null) {
            l.getShares().forEach(s -> s.setTrfNumber(get(tr, s.getTrfId())));
          }
        }
      }
    }
  }

  void waves(@Nullable List<Wave> ws) {
    if (ws == null || ws.isEmpty()) {
      return;
    }
    Set<String> waveIds = new HashSet<>();
    Set<String> trfIds = new HashSet<>();
    for (Wave w : ws) {
      add(waveIds, w.getWaveId());
      if (w.getTrfIds() != null) {
        trfIds.addAll(w.getTrfIds());
      }
    }
    Map<String, String> wn = numbers.numbers(Kind.WAVE, waveIds);
    Map<String, String> tr = numbers.numbers(Kind.TRF, trfIds);
    var holds = adjustments.holds(waveIds);
    for (Wave w : ws) {
      w.setWaveNumber(get(wn, w.getWaveId()));
      var h = w.getWaveId() == null ? null : holds.get(w.getWaveId());
      if (h != null) {
        w.setHold(
            new com.pmart.cdc.outbound.api.generated.model.WaveHold()
                .reason(h.reason())
                .heldBy(h.by())
                .heldAt(h.at().atOffset(java.time.ZoneOffset.UTC)));
      }
      if (w.getTrfIds() != null) {
        w.setTrfNumbers(
            new ArrayList<>(w.getTrfIds().stream().map(id -> tr.getOrDefault(id, "")).toList()));
      }
    }
  }

  void summaries(@Nullable List<TransferSummary> ss) {
    if (ss == null || ss.isEmpty()) {
      return;
    }
    Set<String> trfIds = new HashSet<>();
    Set<String> waveIds = new HashSet<>();
    for (TransferSummary s : ss) {
      add(trfIds, s.getTrfId());
      add(waveIds, s.getWaveId());
    }
    Map<String, String> tr = numbers.numbers(Kind.TRF, trfIds);
    Map<String, String> wn = numbers.numbers(Kind.WAVE, waveIds);
    for (TransferSummary s : ss) {
      s.setTrfNumber(get(tr, s.getTrfId()));
      s.setWaveNumber(get(wn, s.getWaveId()));
    }
  }

  void detail(TransferDetail d) {
    d.setTrfNumber(get(numbers.numbers(Kind.TRF, list(d.getTrfId())), d.getTrfId()));
    d.setWaveNumber(get(numbers.numbers(Kind.WAVE, list(d.getWaveId())), d.getWaveId()));
    if (d.getLines() != null) {
      Map<String, String> n =
          names.of(d.getLines().stream().map(l -> l.getSku()).filter(x -> x != null).toList());
      d.getLines().forEach(l -> l.setProductName(get(n, l.getSku())));
    }
  }

  void departures(@Nullable List<DepartureValidation> ds) {
    if (ds == null || ds.isEmpty()) {
      return;
    }
    Set<String> depIds = new HashSet<>();
    Set<String> trfIds = new HashSet<>();
    for (DepartureValidation d : ds) {
      add(depIds, d.getDepartureId());
      if (d.getTransfers() != null) {
        d.getTransfers().forEach(t -> add(trfIds, t.getTrfId()));
      }
    }
    Map<String, String> dn = numbers.numbers(Kind.DEPARTURE, depIds);
    Map<String, String> tr = numbers.numbers(Kind.TRF, trfIds);
    for (DepartureValidation d : ds) {
      d.setDepartureNumber(get(dn, d.getDepartureId()));
      if (d.getTransfers() != null) {
        d.getTransfers().forEach(t -> t.setTrfNumber(get(tr, t.getTrfId())));
      }
    }
  }

  /** 0.31.0 DN-1 결품 목록. */
  void shortages(ListCenterShortages200Response l) {
    if (l.getItems() == null || l.getItems().isEmpty()) {
      return;
    }
    Set<String> trfIds = new HashSet<>();
    Set<String> waveIds = new HashSet<>();
    Set<String> taskIds = new HashSet<>();
    for (var x : l.getItems()) {
      add(trfIds, x.getTrfId());
      add(waveIds, x.getWaveId());
      add(taskIds, x.getTaskId());
      if (x.getNewTaskIds() != null) {
        x.getNewTaskIds().forEach(id -> add(taskIds, id));
      }
    }
    Map<String, String> tr = numbers.numbers(Kind.TRF, trfIds);
    Map<String, String> wn = numbers.numbers(Kind.WAVE, waveIds);
    Map<String, String> k = numbers.numbers(Kind.TASK, taskIds);
    for (var x : l.getItems()) {
      x.setTrfNumber(get(tr, x.getTrfId()));
      x.setWaveNumber(get(wn, x.getWaveId()));
      x.setTaskNumber(get(k, x.getTaskId()));
      if (x.getNewTaskIds() != null) {
        x.setNewTaskNumbers(
            new ArrayList<>(x.getNewTaskIds().stream().map(id -> k.getOrDefault(id, "")).toList()));
      }
    }
  }

  /** 0.31.0 DN-2 LPN 목록. */
  void lpns(ListCenterLpns200Response l) {
    if (l.getItems() == null || l.getItems().isEmpty()) {
      return;
    }
    Set<String> trfIds = new HashSet<>();
    Set<String> waveIds = new HashSet<>();
    Set<String> depIds = new HashSet<>();
    for (var x : l.getItems()) {
      add(trfIds, x.getTrfId());
      add(waveIds, x.getWaveId());
      add(depIds, x.getDepartureId());
    }
    Map<String, String> tr = numbers.numbers(Kind.TRF, trfIds);
    Map<String, String> wn = numbers.numbers(Kind.WAVE, waveIds);
    Map<String, String> dn = numbers.numbers(Kind.DEPARTURE, depIds);
    for (var x : l.getItems()) {
      x.setTrfNumber(get(tr, x.getTrfId()));
      x.setWaveNumber(get(wn, x.getWaveId()));
      x.setDepartureNumber(get(dn, x.getDepartureId()));
    }
  }

  private static void add(Set<String> s, @Nullable String v) {
    if (v != null && !v.isBlank()) {
      s.add(v);
    }
  }

  private static List<String> list(@Nullable String v) {
    return v == null ? List.of() : List.of(v);
  }

  private static @Nullable String get(Map<String, String> m, @Nullable String key) {
    return key == null ? null : m.get(key);
  }
}
