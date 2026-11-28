package com.pmart.assistant.infrastructure;

import com.pmart.assistant.application.KnowledgeApprovals;
import com.pmart.common.messaging.EventEnvelope;
import com.pmart.common.messaging.EventEnvelopeCodec;
import com.pmart.common.messaging.EventSchemaValidator;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

/**
 * 승인 연동(ADR-0092): approval-service 의 ApprovalDecided 구독(토픽 platform.approval.v1)과 반영 회신
 * ApplicationReceiptRecorded(platform.approval-receipt.v1) 보내기 — search-service 동의어(ADR-0073 단계 B)와
 * 같은 방식.
 *
 * <p>구독 빈은 바인딩 설정(설정 저장소 ai-assistant-service.yml {@code approvalEvents-in-0})이 있을 때만 만든다 — 설정을 올리기
 * 전 배포에서도 뜨고, 올린 뒤 재시작하면 받는다. 멱등은 {@link KnowledgeApprovals}(반영 기록). 회신은 outbox 없이 StreamBridge 로
 * 바로(rag-pgvector 에 Debezium 연결을 두지 않는다 — 반영 기록을 먼저 남기고 보낸다).
 */
@Configuration(proxyBeanMethods = false)
public class ApprovalMessaging {

  public static final String RECEIPT_TOPIC = "platform.approval-receipt.v1";

  /** platform.approval.v1 — 바인딩 approvalEvents-in-0(설정 저장소). */
  @Bean
  @ConditionalOnProperty(name = "spring.cloud.stream.bindings.approvalEvents-in-0.destination")
  Consumer<Message<byte[]>> approvalEvents(EventEnvelopeCodec codec, KnowledgeApprovals approvals) {
    return message ->
        approvals.onApprovalDecided(
            codec.decode(new String(message.getPayload(), StandardCharsets.UTF_8)));
  }

  @Bean
  KnowledgeApprovals.Receipts approvalReceipts(
      ObjectProvider<StreamBridge> bridge,
      EventEnvelopeCodec codec,
      EventSchemaValidator validator,
      Environment env) {
    String source = env.getProperty("spring.application.name", "ai-assistant-service");
    return new KnowledgeApprovals.Receipts() {
      @Override
      public String sourceSystem() {
        return source;
      }

      @Override
      public void send(String approvalId, Map<String, Object> payload) {
        EventEnvelope envelope =
            codec.create(
                source,
                "ApplicationReceiptRecorded",
                1,
                "ApplicationReceipt",
                approvalId,
                1,
                new EventEnvelope.Actor(EventEnvelope.ActorType.SYSTEM, source),
                EventEnvelope.EventSource.REAL,
                payload);
        validator.validate(envelope);
        var message =
            MessageBuilder.withPayload(codec.encode(envelope).getBytes(StandardCharsets.UTF_8))
                .setHeader(KafkaHeaders.KEY, approvalId.getBytes(StandardCharsets.UTF_8))
                .build();
        StreamBridge b = bridge.getIfAvailable();
        if (b == null || !b.send(RECEIPT_TOPIC, message)) {
          throw new IllegalStateException(
              "승인 회신을 " + RECEIPT_TOPIC + " 로 보내지 못했습니다: " + approvalId);
        }
      }
    };
  }
}
