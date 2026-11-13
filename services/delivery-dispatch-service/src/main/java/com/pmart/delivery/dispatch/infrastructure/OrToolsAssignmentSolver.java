package com.pmart.delivery.dispatch.infrastructure;

import com.google.ortools.Loader;
import com.google.ortools.sat.BoolVar;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.CpSolverStatus;
import com.google.ortools.sat.LinearExpr;
import com.google.ortools.sat.LinearExprBuilder;
import com.pmart.delivery.dispatch.application.AssignmentSolver;
import com.pmart.delivery.dispatch.domain.AssignmentProblem;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * OR-Tools CP-SAT 배정(AI 추천 배차의 임시 계산 — Python AI 최적화 서버 전까지, 사용자 결정 2026-11-13·ADR-0058). x[r][d] ∈
 * {0,1}, 배송마다 라이더 ≤ 1, 라이더마다 배송 ≤ 1, 목적 = 짝 수 × 큰 수 − 비용({@link AssignmentProblem#cost}). 규칙 기반 해를
 * 힌트로 준다.
 *
 * <p>네이티브 라이브러리를 못 올리면(예: Windows 앱 제어 — mfc A6) 규칙 기반 해로 계산하고 ERROR 를 한 번 남긴다(배차를 멈추지 않는다). 이 서비스는
 * ADR-0053 대상(JVM).
 */
@Component
public class OrToolsAssignmentSolver implements AssignmentSolver {

  private static final Logger log = LoggerFactory.getLogger(OrToolsAssignmentSolver.class);
  private static final long MATCH_BONUS = 10_000_000L;

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
  public AssignmentProblem.Solution solve(AssignmentProblem p, Duration timeLimit) {
    AssignmentProblem.Solution hint = p.greedy();
    if (p.riders() == 0 || p.deliveries() == 0 || !load()) {
      return hint;
    }
    CpModel model = new CpModel();
    BoolVar[][] x = new BoolVar[p.riders()][p.deliveries()];
    LinearExprBuilder objective = LinearExpr.newBuilder();
    for (int r = 0; r < p.riders(); r++) {
      for (int d = 0; d < p.deliveries(); d++) {
        if (p.allowed(r, d)) {
          x[r][d] = model.newBoolVar("x_" + r + "_" + d);
          objective.addTerm(x[r][d], MATCH_BONUS - Math.min(MATCH_BONUS - 1, p.cost(r, d)));
          model.addHint(x[r][d], hint.riderForDelivery()[d] == r ? 1 : 0);
        }
      }
    }
    for (int d = 0; d < p.deliveries(); d++) {
      List<BoolVar> col = new ArrayList<>();
      for (int r = 0; r < p.riders(); r++) {
        if (x[r][d] != null) {
          col.add(x[r][d]);
        }
      }
      if (!col.isEmpty()) {
        model.addAtMostOne(col.toArray(new BoolVar[0]));
      }
    }
    for (int r = 0; r < p.riders(); r++) {
      List<BoolVar> row = new ArrayList<>();
      for (int d = 0; d < p.deliveries(); d++) {
        if (x[r][d] != null) {
          row.add(x[r][d]);
        }
      }
      if (!row.isEmpty()) {
        model.addAtMostOne(row.toArray(new BoolVar[0]));
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
    int[] out = new int[p.deliveries()];
    Arrays.fill(out, -1);
    for (int r = 0; r < p.riders(); r++) {
      for (int d = 0; d < p.deliveries(); d++) {
        if (x[r][d] != null && solver.booleanValue(x[r][d])) {
          out[d] = r;
        }
      }
    }
    return new AssignmentProblem.Solution(out, "CP_SAT_" + status.name());
  }
}
