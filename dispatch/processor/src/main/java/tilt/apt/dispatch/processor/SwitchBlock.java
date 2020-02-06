package tilt.apt.dispatch.processor;

import java.util.HashSet;
import java.util.Set;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;

final class SwitchBlock {
  final TypeElement typeElement;
  private final ParameterInMethod switchParameter;
  private final Set<ParameterInMethod> caseParameters;

  public SwitchBlock(final TypeElement typeElement) {
    this(typeElement, null);
  }

  public SwitchBlock(final TypeElement typeElement, final ParameterInMethod switchParameter) {
    this.typeElement = typeElement;
    this.switchParameter = switchParameter;
    this.caseParameters = new HashSet<>();
  }

  public void addCaseParameter(final ParameterInMethod caseParameter) {
    this.caseParameters.add(caseParameter);
  }

  public void copyCaseParametersTo(final SwitchBlock other) {
    for (final ParameterInMethod caseParameter : caseParameters) {
      other.addCaseParameter(caseParameter);
    }
  }
  
  boolean hasSwitch() {
    return switchParameter != null;
  }

  ExecutableElement getSwitchMethodElement() {
    return switchParameter.methodInType.methodElement;
  }

  Name getSwitchParameterName() {
    return switchParameter.variableElement.getSimpleName();
  }

  Set<ParameterInMethod> getCaseParameters() {
    return caseParameters;
  }

  @Override
  public String toString() {
    return String.format("%s => %s", switchParameter, caseParameters);
  }
}
