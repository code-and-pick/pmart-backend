package com.pmart.hr.api;

import com.pmart.hr.api.generated.LabelsApi;
import com.pmart.hr.api.generated.model.EmployeeLabel;
import com.pmart.hr.api.generated.model.EmployeeLabelList;
import com.pmart.hr.application.EmployeeService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** 계약 LabelsApi — 운영자 ID → 이름·사번 표시(0.8.0, 모든 직원). 응답의 이름은 로그에 남기지 않는다. */
@RestController
public class LabelsController implements LabelsApi {

  private final EmployeeService employees;

  public LabelsController(EmployeeService employees) {
    this.employees = employees;
  }

  @Override
  public ResponseEntity<EmployeeLabelList> getEmployeeLabels(
      @org.jspecify.annotations.Nullable List<String> operatorIds,
      @org.jspecify.annotations.Nullable List<String> employeeNos,
      @org.jspecify.annotations.Nullable Boolean activeOnly) {
    return ResponseEntity.ok(
        new EmployeeLabelList()
            .items(
                employees.labels(operatorIds, employeeNos, Boolean.TRUE.equals(activeOnly)).stream()
                    .map(
                        l ->
                            new EmployeeLabel()
                                .operatorId(l.operatorId())
                                .name(l.name())
                                .employeeNo(l.employeeNo())
                                .status(
                                    l.status() == null
                                        ? null
                                        : EmployeeLabel.StatusEnum.fromValue(l.status())))
                    .toList()));
  }
}
