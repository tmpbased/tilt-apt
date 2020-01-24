package tilt.apt.dispatch.processor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;

public class AppendableWriter implements Appendable {
  private final Writer writer;

  public AppendableWriter(final Writer writer) {
    this.writer = writer;
  }

  @Override
  public void append(final CharSequence cs) {
    try {
      this.writer.append(cs);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
