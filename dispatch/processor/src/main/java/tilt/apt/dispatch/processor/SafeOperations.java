package tilt.apt.dispatch.processor;

import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeVariable;

final class SafeOperations {
  static TypeElement asElement(DeclaredType t) {
    return (TypeElement) t.asElement();
  }

  static TypeParameterElement asElement(TypeVariable t) {
    return (TypeParameterElement) t.asElement();
  }

  static String getQualifiedName(TypeElement typeElement) {
    return typeElement.getQualifiedName().toString();
  }

  static String getSimpleName(TypeParameterElement typeElement) {
    return typeElement.getSimpleName().toString();
  }
}
