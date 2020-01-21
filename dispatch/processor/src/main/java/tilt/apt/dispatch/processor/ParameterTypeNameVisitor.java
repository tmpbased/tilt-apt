package tilt.apt.dispatch.processor;

import static tilt.apt.dispatch.processor.SafeCasts.asElement;

import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeVariable;

final class ParameterTypeNameVisitor extends TypeNameVisitor {
  static final TypeNameVisitor INSTANCE = new ParameterTypeNameVisitor();

  private ParameterTypeNameVisitor() {}

  @Override
  public String visitDeclared(DeclaredType t, Void p) {
    return asElement(t).getQualifiedName()
        + ArgumentTypeNameVisitor.INSTANCE.formatTypeArguments(t);
  }

  @Override
  public String visitTypeVariable(TypeVariable t, Void p) {
    final String upperBoundName = t.getUpperBound().accept(ArgumentTypeNameVisitor.INSTANCE, null);
    return asElement(t).getSimpleName()
        + (upperBoundName == null || upperBoundName.equals("java.lang.Object")
            ? ""
            : " extends " + upperBoundName);
  }
}
