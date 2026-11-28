package com.pmart.embedding.infrastructure;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/** 시험·로컬 전용(provider=fake): 글의 SHA-256 으로 만든 길이 1 벡터. 같은 글 = 같은 벡터, 뜻은 없다. LAN 에서는 쓰지 않는다. */
public final class FakeEmbedding implements EmbeddingModel {

  private final int dim;

  public FakeEmbedding(int dim) {
    this.dim = dim;
  }

  @Override
  public EmbeddingResponse call(EmbeddingRequest request) {
    List<Embedding> out = new ArrayList<>();
    List<String> in = request.getInstructions();
    for (int i = 0; i < in.size(); i++) {
      out.add(new Embedding(vector(in.get(i)), i));
    }
    return new EmbeddingResponse(out);
  }

  @Override
  public float[] embed(Document document) {
    return vector(document.getText() == null ? "" : document.getText());
  }

  float[] vector(String text) {
    try {
      byte[] seed =
          MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      float[] v = new float[dim];
      double norm = 0;
      for (int i = 0; i < dim; i++) {
        v[i] = (seed[i % seed.length] ^ (i * 31)) / 128f;
        norm += v[i] * v[i];
      }
      float n = (float) Math.sqrt(norm == 0 ? 1 : norm);
      for (int i = 0; i < dim; i++) {
        v[i] /= n;
      }
      return v;
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
