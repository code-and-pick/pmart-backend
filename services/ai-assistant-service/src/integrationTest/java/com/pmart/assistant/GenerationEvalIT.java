package com.pmart.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.assistant.application.AssistantProperties;
import com.pmart.assistant.application.EmbeddingSentenceSimilarity;
import com.pmart.assistant.application.ExtractiveAnswerGenerator;
import com.pmart.assistant.application.GenerativeAnswerGenerator;
import com.pmart.assistant.application.KnowledgeJobs;
import com.pmart.assistant.application.Retriever;
import com.pmart.assistant.domain.CitationVerifier;
import com.pmart.assistant.domain.QuestionClassifier;
import com.pmart.assistant.domain.RetrievedChunk;
import com.pmart.assistant.infrastructure.OllamaModels;
import com.pmart.assistant.infrastructure.SnapshotStore;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 생성·인용 검사 측정(C2, 2026-11-27) — 평소 시험에서는 아무것도 하지 않는다.
 *
 * <ul>
 *   <li>{@code -PevalChatModel=qwen3:4b -PevalOllamaModel=qwen3-embedding:0.6b
 *       -PevalOllamaUrl=...}: 실제 저장소 문서를 색인하고(임베딩 파일 캐시), 답변 평가 질문(eval/answers.json)마다 LAN 과 같은
 *       설정으로 실제 답을 생성한다. 시도마다 모델 답 원문·인용 ID·근거·문장별 의미 유사도·지금 기준의 검사 결과를 {@code
 *       build/reports/eval/generation/records.json} 에 남긴다.
 *   <li>{@code -PevalGenerationSweep=true}: 위 기록과 {@code eval/generation-labels.json}(사람이 근거와 대조해
 *       붙인 맞음/틀림)으로 기준값(bigram 하한·의미 유사도 하한)을 바꿔 가며 "옳은 답 잘못 거절"·"틀린 답 통과"를 센다. 모델을 부르지 않는다.
 * </ul>
 *
 * 운영 코드는 바꾸지 않는다: 모델 답은 채팅 모델을 감싸 기록하고, 문장별 유사도는 bigram 하한을 1 보다 크게 둔 검사기로(모든 문장을 약한 문장으로 보아) 한 번 더
 * 돌려 받는다. 질문·답 원문은 저장소 문서에 대한 시험 질문이라 개인정보가 없다.
 */
class GenerationEvalIT extends AssistantITBase {

  static final Path REPO = Path.of("../..").toAbsolutePath().normalize();
  static final Path OUT = Path.of("build/reports/eval/generation");
  static final Path RECORDS = OUT.resolve("records.json");

  /** 지금 LAN 기준(pmart-config ai-assistant-service.yml·-lan.yml, 2026-11-27). */
  static final double LAN_MIN_OVERLAP = 0.5;

  static final double LAN_MIN_SIMILARITY = 0.65;
  static final double LAN_THRESHOLD = 0.40;
  static final double LAN_FALLBACK_MIN = 0.45;

  @Autowired Retriever retriever;
  @Autowired SnapshotStore snapshots;
  @Autowired AssistantProperties props;

  /** 채팅 모델을 감싸 시도마다 원문·걸린 시간을 남긴다. */
  static final class RecordingChat implements ChatModel {
    final ChatModel delegate;
    final List<String> raws = new CopyOnWriteArrayList<>();
    final List<Double> seconds = new CopyOnWriteArrayList<>();

    RecordingChat(ChatModel delegate) {
      this.delegate = delegate;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
      long t0 = System.nanoTime();
      ChatResponse r = delegate.call(prompt);
      seconds.add((System.nanoTime() - t0) / 1e9);
      String text =
          r == null || r.getResult() == null || r.getResult().getOutput() == null
              ? null
              : r.getResult().getOutput().getText();
      raws.add(text == null ? "" : text);
      return r;
    }
  }

  /** 문장 → 최대 유사도를 받아 두고, 같은 문장은 다시 재지 않는다. */
  static final class RecordingSimilarity implements CitationVerifier.SentenceSimilarity {
    final CitationVerifier.@Nullable SentenceSimilarity delegate;
    final Map<String, Double> best = new LinkedHashMap<>();

    RecordingSimilarity(CitationVerifier.@Nullable SentenceSimilarity delegate) {
      this.delegate = delegate;
    }

    @Override
    public double[] best(List<String> sentences, List<String> evidenceUnits) {
      double[] out = new double[sentences.size()];
      List<String> missing = new ArrayList<>();
      for (String s : sentences) {
        if (!best.containsKey(s)) {
          missing.add(s);
        }
      }
      if (!missing.isEmpty() && delegate != null) {
        double[] fresh = delegate.best(missing, evidenceUnits);
        for (int i = 0; i < missing.size(); i++) {
          best.put(missing.get(i), fresh[i]);
        }
      }
      for (int i = 0; i < out.length; i++) {
        out[i] = best.getOrDefault(sentences.get(i), 0.0);
      }
      return out;
    }
  }

  @Test
  void generateAndRecord() throws IOException {
    String chatModel = System.getProperty("pmart.eval.chat-model", "");
    if (chatModel.isBlank()) {
      return; // 평소 시험: 아무것도 하지 않는다
    }
    assertThat(embedding.eval).as("-PevalOllamaModel 도 함께 줘야 한다(의미 유사도)").isNotNull();
    var r = props.getRetrieval();
    // 같은 Spring 문맥을 다른 시험과 나눠 쓰므로 끝나면 원래 값으로 돌린다
    double[] saved = {
      r.getThreshold(),
      r.getFallbackMinSimilarity(),
      r.getConfirmedWeight(),
      r.getAdoptedWeight(),
      r.getProposalWeight(),
      r.getAssumptionWeight(),
      r.getPrecedenceWeight()
    };
    try {
      // LAN 값(pmart-config ai-assistant-service-lan.yml, 2026-11-27)
      r.setThreshold(LAN_THRESHOLD);
      r.setFallbackMinSimilarity(LAN_FALLBACK_MIN);
      r.setConfirmedWeight(0.03);
      r.setAdoptedWeight(0.02);
      r.setProposalWeight(0.01);
      r.setAssumptionWeight(0.005);
      r.setPrecedenceWeight(0.005);
      generateAndRecordWithLanSettings(chatModel, r);
    } finally {
      r.setThreshold(saved[0]);
      r.setFallbackMinSimilarity(saved[1]);
      r.setConfirmedWeight(saved[2]);
      r.setAdoptedWeight(saved[3]);
      r.setProposalWeight(saved[4]);
      r.setAssumptionWeight(saved[5]);
      r.setPrecedenceWeight(saved[6]);
    }
  }

  void generateAndRecordWithLanSettings(String chatModel, AssistantProperties.Retrieval r)
      throws IOException {

    long t0 = System.nanoTime();
    var result = ingestor.ingest(REPO, KnowledgeJobs.loadManifest());
    double ingestSeconds = (System.nanoTime() - t0) / 1e9;
    var snapshot = snapshots.find(result.snapshotId()).orElseThrow();

    var o = new AssistantProperties.Ollama();
    o.setBaseUrl(System.getProperty("pmart.eval.ollama-url", "http://localhost:11434"));
    o.setChatModel(chatModel);
    o.setChatTemperature(0.2);
    o.setChatNumCtx(8192);
    o.setDisableThinking(true);
    o.setKeepAlive("24h");
    o.setRequestTimeout(Duration.ofSeconds(25));
    RecordingChat chat =
        new RecordingChat(OllamaModels.chat(OllamaModels.api(o, RestClient.builder()), o));
    var semantic = new EmbeddingSentenceSimilarity(embedding);
    var generator =
        new GenerativeAnswerGenerator(
            chat,
            "ollama:" + chatModel,
            new CitationVerifier(LAN_MIN_OVERLAP, LAN_MIN_SIMILARITY, semantic),
            new ExtractiveAnswerGenerator(r.getMaxAnswerChars(), r.getTopK()),
            json,
            () -> LAN_FALLBACK_MIN);

    ArrayNode records = json.createArrayNode();
    for (JsonNode q : answerQuestions()) {
      String id = q.path("id").asString();
      String expect = q.path("expect").asString();
      String text = q.path("q").asString();
      var cls = QuestionClassifier.classify(text);
      ObjectNode rec = records.addObject();
      rec.put("id", id).put("expect", expect).put("q", text);
      if ("REALTIME".equals(expect) || cls.intent() != QuestionClassifier.Intent.POLICY) {
        rec.put("stage", "CLASSIFIED_" + cls.intent());
        continue;
      }
      Retriever.Retrieval ret =
          retriever.retrieve(snapshot, text, cls.history(), Set.of("operator"));
      if (ret.ranked().isEmpty()) {
        rec.put("stage", "BELOW_THRESHOLD");
        continue;
      }
      rec.put("topSimilarity", ret.ranked().get(0).similarity());
      Map<String, String> evidence = new LinkedHashMap<>();
      ObjectNode ev = rec.putObject("evidence");
      for (int i = 0; i < ret.ranked().size(); i++) {
        RetrievedChunk c = ret.ranked().get(i);
        evidence.put("D" + (i + 1), c.content());
        ev.putObject("D" + (i + 1))
            .put("path", c.path())
            .put("section", c.section())
            .put("content", c.content());
      }
      int before = chat.raws.size();
      long g0 = System.nanoTime();
      var generated = generator.generate(text, ret.ranked(), ret.companions());
      rec.put("stage", "GENERATED")
          .put("finalMode", generated.mode().name())
          .put("finalGrounded", generated.grounded())
          .put("seconds", (System.nanoTime() - g0) / 1e9)
          .put("finalText", generated.text());
      ArrayNode attempts = rec.putArray("attempts");
      for (int a = before; a < chat.raws.size(); a++) {
        ObjectNode at = attempts.addObject();
        at.put("attempt", a - before + 1)
            .put("raw", chat.raws.get(a))
            .put("chatSeconds", chat.seconds.get(a));
        JsonNode parsed = parse(chat.raws.get(a));
        if (parsed == null) {
          at.put("parsed", false);
          continue;
        }
        String answer = parsed.path("answer").asString();
        List<String> ids = new ArrayList<>();
        parsed.path("usedIds").forEach(x -> ids.add(x.asString()));
        at.put("parsed", true)
            .put("answer", answer)
            .put("grounded", !parsed.has("grounded") || parsed.path("grounded").asBoolean());
        ids.forEach(at.putArray("usedIds")::add);
        // 모든 문장의 유사도를 받아 둔다(bigram 하한 > 1 → 모든 문장이 약한 문장)
        var recSim = new RecordingSimilarity(semantic);
        new CitationVerifier(1.01, 2.0, recSim).verify(answer, ids, evidence);
        ObjectNode sims = at.putObject("sentenceSimilarity");
        recSim.best.forEach(sims::put);
        var now =
            new CitationVerifier(LAN_MIN_OVERLAP, LAN_MIN_SIMILARITY, recSim)
                .verify(answer, ids, evidence);
        at.put("validNow", now.valid());
        ArrayNode violations = at.putArray("violationsNow");
        now.violations().forEach(v -> violations.add(v.name()));
      }
    }
    Files.createDirectories(OUT);
    ObjectNode root = json.createObjectNode();
    root.put("chatModel", chatModel)
        .put("embedding", embedding.spec().describe())
        .put("chunks", result.chunkCount())
        .put("ingestSeconds", ingestSeconds)
        .put("embeddingCacheHits", OllamaEvalEmbedding.cacheHits)
        .set("records", records);
    Files.writeString(
        RECORDS,
        json.writerWithDefaultPrettyPrinter().writeValueAsString(root),
        StandardCharsets.UTF_8);
    Files.writeString(OUT.resolve("records.md"), recordsMarkdown(root), StandardCharsets.UTF_8);
    assertThat(records.size()).isGreaterThan(0);
  }

  @Test
  void sweepFromRecords() throws IOException {
    if (!Boolean.getBoolean("pmart.eval.generation-sweep")) {
      return;
    }
    JsonNode root = json.readTree(Files.readString(RECORDS, StandardCharsets.UTF_8));
    Map<String, String> labels = new HashMap<>();
    try (InputStream in = new ClassPathResource("eval/generation-labels.json").getInputStream()) {
      json.readTree(in)
          .path("labels")
          .forEach(l -> labels.put(l.path("key").asString(), l.path("label").asString()));
    }
    double[] overlaps = {0.4, 0.5, 0.6};
    double[] sims = {0.55, 0.575, 0.6, 0.625, 0.65, 0.675, 0.7, 0.725, 0.75};
    StringBuilder sb =
        new StringBuilder("# 인용 검사 기준값 다시 계산 (기록 ")
            .append(root.path("chatModel").asString())
            .append(")\n\n");
    sb.append(
        "- 라벨: OK = 근거로 성립하는 옳은 답, WRONG = 근거와 다르거나 지어낸 답, PARTIAL = 옳지만 핵심이 빠짐(통과해도 됨으로 셈)\n");
    sb.append(
        "- 잘못 거절 = OK·PARTIAL 인데 검사 실패, 잘못 통과 = WRONG 인데 검사 통과. 지금 LAN 은 bigram 0.5·유사도 0.65\n\n");
    sb.append(
        "| bigram 하한 | 유사도 하한 | 잘못 거절 | 잘못 통과 | 잘못 거절한 시도 | 잘못 통과한 시도 |\n|---|---|---|---|---|---|\n");
    int labelled = 0;
    for (double ov : overlaps) {
      for (double si : sims) {
        int falseReject = 0;
        int falsePass = 0;
        List<String> passedWrong = new ArrayList<>();
        List<String> rejectedRight = new ArrayList<>();
        labelled = 0;
        for (JsonNode rec : root.path("records")) {
          if (!rec.has("attempts")) {
            continue;
          }
          Map<String, String> evidence = new LinkedHashMap<>();
          rec.path("evidence")
              .properties()
              .forEach(e -> evidence.put(e.getKey(), e.getValue().path("content").asString()));
          for (JsonNode at : rec.path("attempts")) {
            String key = rec.path("id").asString() + "#" + at.path("attempt").asInt();
            String label = labels.get(key);
            if (label == null
                || !at.path("parsed").asBoolean()
                || !at.path("grounded").asBoolean()) {
              continue;
            }
            labelled++;
            var recSim = new RecordingSimilarity(null);
            at.path("sentenceSimilarity")
                .properties()
                .forEach(e -> recSim.best.put(e.getKey(), e.getValue().asDouble()));
            List<String> ids = new ArrayList<>();
            at.path("usedIds").forEach(x -> ids.add(x.asString()));
            boolean valid =
                new CitationVerifier(ov, si, recSim)
                    .verify(at.path("answer").asString(), ids, evidence)
                    .valid();
            if (!valid && !"WRONG".equals(label)) {
              falseReject++;
              rejectedRight.add(key);
            }
            if (valid && "WRONG".equals(label)) {
              falsePass++;
              passedWrong.add(key);
            }
          }
        }
        sb.append(
            String.format(
                Locale.ROOT,
                "| %.2f | %.3f | %d | %d | %s | %s |%n",
                ov,
                si,
                falseReject,
                falsePass,
                String.join(" ", rejectedRight),
                String.join(" ", passedWrong)));
      }
    }
    sb.append("\n- 라벨 붙은 시도: ").append(labelled).append("\n");
    Files.writeString(OUT.resolve("citation-sweep.md"), sb.toString(), StandardCharsets.UTF_8);
    assertThat(labelled).as("라벨과 기록의 key(id#시도)가 맞아야 한다").isGreaterThan(0);
  }

  @Nullable JsonNode parse(String raw) {
    String s = raw.strip();
    if (s.startsWith("```")) {
      s = s.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
    }
    try {
      JsonNode n = json.readTree(s);
      return n.has("answer") && n.has("usedIds") ? n : null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  List<JsonNode> answerQuestions() throws IOException {
    try (InputStream in = new ClassPathResource("eval/answers.json").getInputStream()) {
      List<JsonNode> out = new ArrayList<>();
      json.readTree(in).path("questions").forEach(out::add);
      return out;
    }
  }

  static String recordsMarkdown(JsonNode root) {
    StringBuilder sb = new StringBuilder("# 생성·인용 검사 기록\n\n");
    sb.append("- 모델: ")
        .append(root.path("chatModel").asString())
        .append(" · 임베딩 ")
        .append(root.path("embedding").asString())
        .append('\n');
    sb.append(
        String.format(
            Locale.ROOT,
            "- 색인 %d청크 %.0f초 (임베딩 캐시 적중 %d)%n%n",
            root.path("chunks").asInt(),
            root.path("ingestSeconds").asDouble(),
            root.path("embeddingCacheHits").asInt()));
    for (JsonNode rec : root.path("records")) {
      sb.append("## ")
          .append(rec.path("id").asString())
          .append(" (")
          .append(rec.path("expect").asString())
          .append(") ")
          .append(rec.path("q").asString())
          .append('\n');
      sb.append("- 단계: ").append(rec.path("stage").asString());
      if (rec.has("topSimilarity")) {
        sb.append(
            String.format(
                Locale.ROOT,
                " · 1위 %.3f · 최종 %s · %.1f초",
                rec.path("topSimilarity").asDouble(),
                rec.path("finalMode").asString(),
                rec.path("seconds").asDouble()));
      }
      sb.append('\n');
      for (JsonNode at : rec.path("attempts")) {
        sb.append(
            String.format(
                Locale.ROOT,
                "- 시도 %d (%.1f초): ",
                at.path("attempt").asInt(),
                at.path("chatSeconds").asDouble()));
        if (!at.path("parsed").asBoolean()) {
          sb.append("JSON 아님\n");
          continue;
        }
        sb.append("검사 ")
            .append(at.path("validNow").asBoolean() ? "통과" : "실패 " + at.path("violationsNow"))
            .append(" · 인용 ")
            .append(at.path("usedIds"))
            .append('\n');
        sb.append("  - 답: ").append(at.path("answer").asString().replace("\n", " ")).append('\n');
        at.path("sentenceSimilarity")
            .properties()
            .forEach(
                e ->
                    sb.append(
                        String.format(
                            Locale.ROOT, "  - %.3f — %s%n", e.getValue().asDouble(), e.getKey())));
      }
      sb.append('\n');
    }
    return sb.toString();
  }
}
