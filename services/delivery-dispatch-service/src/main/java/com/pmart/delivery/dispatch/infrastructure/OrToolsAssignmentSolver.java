package com.pmart.delivery.dispatch.infrastructure;

import com.google.ortools.Loader;
import com.google.ortools.sat.BoolVar;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.CpSolverStatus;
import com.google.ortools.sat.LinearExpr;
import com.google.ortools.sat.LinearExprBuilder;
import com.pmart.delivery.dispatch.application.AssignmentSolver;
import com.pmart.delivery.dispatch.domain.BundleProblem;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * OR-Tools CP-SAT 라이더 ↔ 묶음 배정(AI 추천 배차의 임시 계산 — Python AI 최적화 서버 전까지, 사용자 결정 2026-11-13·ADR-0058).
 * y[r][b] ∈ {0,1}, 라이더마다 묶음 ≤ 1, 배송마다 덮는 묶음 ≤ 1. 목적 = Σ y·(배송 수 × 큰 수 − 비용({@link
 * BundleProblem#cost})). 규칙 기반 해를 힌트로 준다.
 *
 * <p>네이티브 라이브러리를 못 올리면(예: Windows 앱 제어 — mfc A6) 규칙 기반 해로 계산하고 ERROR 를 한 번 남긴다(배차를 멈추지 않는다). 이 서비스는
 * ADR-0053 대상(JVM).
 */
@Component
public class OrToolsAssignmentSolver implements AssignmentSolver {

  private static final Logger log = LoggerFactory.getLogger(OrToolsAssignmentSolver.class);
  private static final long PER_DELIVERY_BONUS = 10_000_000L;

  private static volatile Boolean loaded;

  private static synchronized boolean load() {
    if (loaded == null) {
      try {
        Loader.loadNativeLibraries();
        loaded = true;
      } catch (Throwable t) {
        log.error("OR-Tools 네이티브 라이브러리를 올리지 못함 — 규칙 기반 배정으로 계산한다: {}", t.toString());
        loaded = false;
      }
    }
    return loaded;
  }

  @Override
  public BundleProblem.Solution solve(BundleProblem p, Duration timeLimit) {
    BundleProblem.Solution hint = p.greedy();
    if (p.riders() == 0 || p.bundles().isEmpty() || !load()) {
      return hint;
    }
    int nb = p.bundles().size();
    int nd = p.readyInSeconds().length;
    CpModel model = new CpModel();
    BoolVar[][] y = new BoolVar[p.riders()][nb];
    LinearExprBuilder objective = LinearExpr.newBuilder();
    List<List<BoolVar>> byDelivery = new ArrayList<>();
    for (int d = 0; d < nd; d++) {
      byDelivery.add(new ArrayList<>());
    }
    for (int r = 0; r < p.riders(); r++) {
      List<BoolVar> row = new ArrayList<>();
      for (int b = 0; b < nb; b++) {
        if (!p.allowed(r, b)) {
          continue;
        }
        BoolVar v = model.newBoolVar("y_" + r + "_" + b);
        y[r][b] = v;
        row.add(v);
        BundleProblem.Bundle bundle = p.bundles().get(b);
        long gain =
            PER_DELIVERY_BONUS * bundle.size() - Math.min(PER_DELIVERY_BONUS - 1, p.cost(r, b));
        objective.addTerm(v, gain);
        model.addHint(v, hint.riderForBundle()[b] == r ? 1 : 0);
        for (int d : bundle.deliveries()) {
          byDelivery.get(d).add(v);
        }
      }
      if (!row.isEmpty()) {
        model.addAtMostOne(row.toArray(new BoolVar[0]));
      }
    }
    for (List<BoolVar> col : byDelivery) {
      if (!col.isEmpty()) {
        model.addAtMostOne(col.toArray(new BoolVar[0]));
      }
    }
    model.maximize(objective);
    CpSolver solver = new CpSolver();
    solver.getParameters().setMaxTimeInSeconds(Math.max(0.05, timeLimit.toMillis() / 1000.0));
    solver.getParameters().setNumWorkers(1);
    CpSolverStatus status = solver.solve(model);
    if (status != CpSolverStatus.OPTIMAL && status != CpSolverStatus.FEASIBLE) {
      log.warn("CP-SAT 배정 해 없음({}) — 규칙 기반 해를 쓴다", status);
      return hint;
    }
    int[] out = new int[nb];
    Arrays.fill(out, -1);
    for (int r = 0; r < p.riders(); r++) {
      for (int b = 0; b < nb; b++) {
        if (y[r][b] != null && solver.booleanValue(y[r][b])) {
          out[b] = r;
        }
      }
    }
    return new BundleProblem.Solution(out, "CP_SAT_" + status.name());
  }
}
