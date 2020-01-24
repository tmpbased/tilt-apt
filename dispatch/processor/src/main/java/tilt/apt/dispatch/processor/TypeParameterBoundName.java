package tilt.apt.dispatch.processor;

import static tilt.apt.dispatch.processor.SafeOperations.asElement;

import java.util.Iterator;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;

final class TypeParameterBoundName extends TypeName {
  static final TypeName INSTANCE = new TypeParameterBoundName();

  private TypeParameterBoundName() {}

  @Override
  public Appendable visitDeclared(DeclaredType t, Appendable p) {
    TypeArgumentName.INSTANCE.visitDeclared(t, p);
    return p;
  }

  @Override
  public Appendable visitTypeVariable(TypeVariable t, Appendable p) {
    p.append(asElement(t).getSimpleName());
    return p;
  }

  @Override
  public Appendable visitIntersection(IntersectionType t, Appendable p) {
    for (final Iterator<? extends TypeMirror> it = t.getBounds().iterator(); it.hasNext(); ) {
      final TypeMirror bound = it.next();
      bound.accept(this, p);
      if (it.hasNext()) {
        p.append(" & ");
      }
    }
    return p;
  }
}
