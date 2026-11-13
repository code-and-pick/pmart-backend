package com.pmart.delivery.dispatch.application;

import com.pmart.delivery.dispatch.domain.AssignmentProblem;
import java.time.Duration;

/** AI 추천 배차 계산 포트. 지금은 OR-Tools(임시), Python AI 최적화 서버가 생기면 그 어댑터로 바꾼다(ADR-0058 결정 7). */
public interface AssignmentSolver {

  AssignmentProblem.Solution solve(AssignmentProblem problem, Duration timeLimit);
}
