package com.pmart.embedding.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.pmart.embedding.domain.EsChunk;
import org.junit.jupiter.api.Test;

class VectorStoreTest {

  @Test
  void vectorLiteralRoundTrip() {
    float[] v = {0.25f, -1.5f, 3.0E-4f};
    assertThat(VectorStore.parse(VectorStore.literal(v))).containsExactly(v);
    assertThat(VectorStore.parse("[]")).isEmpty();
  }

  @Test
  void embeddingTextIsSectionPathAndContent() {
    var c = new EsChunk("c1", "d.md", "h", null, "정책 > 제2조 (접수)", 1, "본문");
    assertThat(c.embeddingText()).isEqualTo("정책 > 제2조 (접수)\n\n본문");
    assertThat(new EsChunk("c1", "d.md", "h", null, "", 0, "본문").embeddingText()).isEqualTo("본문");
    assertThat(c.embeddingTextSha256()).hasSize(64);
  }

  @Test
  void fakeEmbeddingIsDeterministicUnitVector() {
    var f = new FakeEmbedding(1024);
    float[] a = f.vector("같은 글");
    assertThat(a).hasSize(1024).containsExactly(f.vector("같은 글"));
    double norm = 0;
    for (float x : a) {
      norm += x * x;
    }
    assertThat(norm).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-3));
  }
}
