package com.pmart.cdc.outbound.api;

import com.pmart.cdc.outbound.api.generated.TransfersApi;
import org.springframework.web.bind.annotation.RestController;

/** 계약 TransfersApi 의 구현 진입점. 아직 구현하지 않은 메서드는 인터페이스 default 가 501 을 돌려준다(0.3.0). */
@RestController
public class TransfersController implements TransfersApi {}
