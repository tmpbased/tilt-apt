package tilt.apt.dispatch.processor;

import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeVariable;

final class SafeCasts {
  static TypeElement asElement(DeclaredType t) {
    return (TypeElement) t.asElement();
  }

  static TypeParameterElement asElement(TypeVariable t) {
    return (TypeParameterElement) t.asElement();
  }
}
