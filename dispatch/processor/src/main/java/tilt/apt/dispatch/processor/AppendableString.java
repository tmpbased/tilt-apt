package tilt.apt.dispatch.processor;

public final class AppendableString implements Appendable {
  private final StringBuilder sb;

  public AppendableString() {
    sb = new StringBuilder();
  }

  @Override
  public void append(CharSequence cs) {
    sb.append(cs);
  }

  @Override
  public String toString() {
    return sb.toString();
  }
}
