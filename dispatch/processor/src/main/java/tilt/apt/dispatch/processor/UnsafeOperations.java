package tilt.apt.dispatch.processor;

import static tilt.apt.dispatch.processor.SafeOperations.asElement;

import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ErrorType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.SimpleTypeVisitor9;

final class UnsafeOperations {
  static TypeElement asTypeElement(final TypeMirror typeMirror) {
    final DeclaredType declaredType = asDeclaredType(typeMirror);
    if (declaredType == null) {
      return null;
    }
    return asElement(declaredType);
  }

  static DeclaredType asDeclaredType(final TypeMirror typeMirror) {
    return typeMirror.accept(
        new SimpleTypeVisitor9<DeclaredType, TypeMirror>() {
          @Override
          public DeclaredType visitDeclared(DeclaredType t, TypeMirror p) {
            return t;
          }

          @Override
          public DeclaredType visitError(ErrorType t, TypeMirror p) {
            return visitDeclared(t, p);
          }
        },
        typeMirror);
  }
}
