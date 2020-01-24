package tilt.apt.dispatch.processor;

import static tilt.apt.dispatch.processor.SafeOperations.asElement;

import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeVariable;

final class TypeParameterName extends TypeName {
  static final TypeName INSTANCE = new TypeParameterName();

  private TypeParameterName() {}

  @Override
  public Appendable visitDeclared(DeclaredType t, Appendable p) {
    TypeArgumentName.INSTANCE.visitDeclared(t, p);
    return p;
  }

  @Override
  public Appendable visitTypeVariable(TypeVariable t, Appendable p) {
    p.append(asElement(t).getSimpleName());
    formatExtendsBound(t, p);
    return p;
  }

  private void formatExtendsBound(TypeVariable t, Appendable p) {
    final var upperBoundName =
        t.getUpperBound()
            .accept(TypeParameterBoundName.INSTANCE, new AppendableString())
            .toString();
    if (upperBoundName.isEmpty() == false
        && upperBoundName.equals(Object.class.getName()) == false) {
      p.append(" extends ");
      p.append(upperBoundName);
    }
  }
}
