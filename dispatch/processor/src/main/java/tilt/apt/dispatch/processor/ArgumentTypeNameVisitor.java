package tilt.apt.dispatch.processor;

import static tilt.apt.dispatch.processor.SafeCasts.asElement;

import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;

final class ArgumentTypeNameVisitor extends TypeNameVisitor {
  static final TypeNameVisitor INSTANCE = new ArgumentTypeNameVisitor();

  private ArgumentTypeNameVisitor() {}

  @Override
  public String visitDeclared(DeclaredType t, Void p) {
    return asElement(t).getQualifiedName() + formatTypeArguments(t);
  }

  @Override
  public String visitTypeVariable(TypeVariable t, Void p) {
    return asElement(t).getSimpleName().toString();
  }

  @Override
  public String visitWildcard(WildcardType t, Void p) {
    return "?" + formatExtendsBound(t) + formatSuperBound(t);
  }

  private String formatExtendsBound(WildcardType t) {
    final TypeMirror bound = t.getExtendsBound();
    return bound == null ? "" : " extends " + bound.accept(this, null);
  }

  private String formatSuperBound(WildcardType t) {
    final TypeMirror bound = t.getSuperBound();
    return bound == null ? "" : " super " + bound.accept(this, null);
  }
}
