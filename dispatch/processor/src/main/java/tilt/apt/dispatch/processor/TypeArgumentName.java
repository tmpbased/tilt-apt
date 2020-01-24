package tilt.apt.dispatch.processor;

import static tilt.apt.dispatch.processor.SafeOperations.asElement;

import java.util.Iterator;
import java.util.List;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.WildcardType;

final class TypeArgumentName extends TypeName {
  static final TypeName INSTANCE = new TypeArgumentName();

  private TypeArgumentName() {}

  @Override
  public Appendable visitDeclared(DeclaredType t, Appendable p) {
    p.append(asElement(t).getQualifiedName());
    formatTypeArguments(t, p);
    return p;
  }

  private void formatTypeArguments(DeclaredType t, Appendable p) {
    final List<? extends TypeMirror> typeArguments = t.getTypeArguments();
    if (typeArguments.isEmpty()) {
      return;
    }
    p.append("<");
    for (final Iterator<? extends TypeMirror> it = typeArguments.iterator(); it.hasNext(); ) {
      final TypeMirror typeArgument = it.next();
      typeArgument.accept(this, p);
      if (it.hasNext()) {
        p.append(", ");
      }
    }
    p.append(">");
  }

  @Override
  public Appendable visitTypeVariable(TypeVariable t, Appendable p) {
    p.append(asElement(t).getSimpleName());
    return p;
  }

  @Override
  public Appendable visitWildcard(WildcardType t, Appendable p) {
    p.append("?");
    formatExtendsBound(t, p);
    formatSuperBound(t, p);
    return p;
  }

  private void formatExtendsBound(WildcardType t, Appendable p) {
    final TypeMirror bound = t.getExtendsBound();
    if (bound != null) {
      p.append(" extends ");
      bound.accept(this, p);
    }
  }

  private void formatSuperBound(WildcardType t, Appendable p) {
    final TypeMirror bound = t.getSuperBound();
    if (bound != null) {
      p.append(" super ");
      bound.accept(this, p);
    }
  }

  @Override
  public Appendable visitArray(ArrayType t, Appendable p) {
    t.getComponentType().accept(this, p);
    p.append("[]");
    return p;
  }
}
