package tilt.apt.dispatch.processor;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.NoType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.SimpleTypeVisitor9;

abstract class TypeNameVisitor extends SimpleTypeVisitor9<String, Void> {
  protected String formatTypeArguments(DeclaredType t) {
    final List<? extends TypeMirror> typeArguments = t.getTypeArguments();
    if (typeArguments.isEmpty()) {
      return "";
    }
    return typeArguments
        .stream()
        .map(it -> it.accept(this, null))
        .filter(Objects::nonNull)
        .collect(Collectors.joining(", ", "<", ">"));
  }
  
  @Override
  public String visitIntersection(IntersectionType t, Void p) {
    return t.getBounds()
        .stream()
        .map(bound -> bound.accept(this, null))
        .filter(Objects::nonNull)
        .collect(Collectors.joining(" & "));
  }

  @Override
  public String visitNoType(NoType t, Void p) {
    if (t.getKind() == TypeKind.VOID) {
      return "void";
    }
    return DEFAULT_VALUE;
  }
}
