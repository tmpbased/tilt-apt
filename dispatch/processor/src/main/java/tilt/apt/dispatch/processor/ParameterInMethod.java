package tilt.apt.dispatch.processor;

import static tilt.apt.dispatch.processor.SafeOperations.isAbstractElement;
import static tilt.apt.dispatch.processor.UnsafeOperations.asTypeElement;

import java.util.Optional;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.Elements;

final class ParameterInMethod {
  final MethodInType methodInType;
  final VariableElement variableElement;

  ParameterInMethod(final Elements elements, final VariableElement variableElement) {
    this.methodInType =
        new MethodInType(elements, (ExecutableElement) variableElement.getEnclosingElement());
    this.variableElement = variableElement;
  }

  ParameterInMethod(final MethodInType methodInType, final VariableElement variableElement) {
    this.methodInType = methodInType;
    this.variableElement = variableElement;
  }

  private boolean isGoodSwitch(final MethodInType methodInType) {
    return isAbstractElement(methodInType.typeElement)
        && isAbstractElement(methodInType.methodElement);
  }

  boolean isGoodSwitch() {
    return isGoodSwitch(methodInType);
  }

  boolean isGoodCase(final MethodInType methodInType) {
    return isAbstractElement(methodInType.typeElement);
  }

  boolean isGoodCase() {
    return isGoodCase(methodInType);
  }

  ParameterInMethod getGoodCase() {
    if (isGoodCase()) {
      return this;
    }
    if (DispatchProcessor.OPTION_INHERIT_CASES) {
      final MethodInType overridable = methodInType.findOverridable(this::isGoodCase);
      final int parameterIndex = methodInType.getMethodParameters().indexOf(variableElement);
      final VariableElement overridableVariableElement =
          overridable.getMethodParameters().get(parameterIndex);
      return new ParameterInMethod(overridable, overridableVariableElement);
    }
    return this;
  }

  TypeElement getTypeElement() {
    return methodInType.typeElement;
  }

  Name getMethodName() {
    return methodInType.methodElement.getSimpleName();
  }

  Name getParameterTypeName() {
    return Optional.ofNullable(asTypeElement(variableElement.asType()))
        .map(TypeElement::getQualifiedName)
        .orElse(null);
  }

  @Override
  public String toString() {
    return String.format("%s(%s)", methodInType, variableElement);
  }
}
