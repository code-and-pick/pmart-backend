-- 0.39.0(2026-11-23 #385, "배송 끝나면 처리 필요 없게 완료") 배포 전에 이미 끝난 배송의 열린 도움요청을 닫는다(2026-11-25 사용자
-- "오래 지체되는 것들은 자동으로 완료되게"). 그때부터는 끝 상태가 되는 자리에서 닫지만, 그 전에 끝난 배송은 열린 채 남아 상담관리에 140시간씩 걸려 있었다.
-- 앱이 닫을 때와 같은 값(resolvedBy system:delivery-ended, 메모 "배송이 끝나 자동 완료"). 사건함 사건은 그대로 둔다(지원센터가 닫는다).
UPDATE help_request h
SET state = 'RESOLVED',
    resolved_at = now(),
    resolved_by = 'system:delivery-ended',
    resolution_note = '배송이 끝나 자동 완료'
FROM execution_delivery d
WHERE d.delivery_id = h.delivery_id
  AND h.state <> 'RESOLVED'
  AND d.state IN ('COMPLETED', 'FAILED', 'CANCELLED', 'RETURNED_TO_STORE');
