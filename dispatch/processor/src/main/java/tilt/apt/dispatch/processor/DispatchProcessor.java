package tilt.apt.dispatch.processor;

import com.google.auto.service.AutoService;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic.Kind;
import javax.tools.FileObject;
import tilt.apt.dispatch.annotations.Case;
import tilt.apt.dispatch.annotations.Switch;

/**
 * Processor Options:
 *
 * <ul>
 *   <li>debug - turns on debug statements
 * </ul>
 */
@AutoService(Processor.class)
@SupportedOptions({"debug"})
public class DispatchProcessor extends AbstractProcessor {
  @Override
  public Set<String> getSupportedAnnotationTypes() {
    return Set.of(Switch.class.getName(), Case.class.getName());
  }

  @Override
  public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latestSupported();
  }

  final Map<TypeMirror, SwitchSubclass> subclasses = new HashMap<>();

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    try {
      return processImpl(annotations, roundEnv);
    } catch (Exception e) {
      // We don't allow exceptions of any kind to propagate to the compiler
      StringWriter writer = new StringWriter();
      e.printStackTrace(new PrintWriter(writer));
      fatalError(writer.toString());
      return true;
    }
  }

  private boolean processImpl(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    if (roundEnv.processingOver()) {
      final var visitedSubclassMirrors = new HashSet<TypeMirror>();
      subclasses.forEach(
          (typeMirror, subclass) -> {
            if (visitedSubclassMirrors.contains(typeMirror)) {
              return;
            }
            final Deque<SwitchSubclass> visitedSuperSubclasses = new LinkedList<>();
            visitedSuperSubclasses.add(subclass);
            TypeMirror superclassMirror = getSuperclassMirror(typeMirror);
            while (superclassMirror != null) {
              final SwitchSubclass superSubclass = this.subclasses.get(superclassMirror);
              if (superSubclass != null) {
                visitedSuperSubclasses.forEach(it -> superSubclass.drainTo(it));
                visitedSuperSubclasses.push(superSubclass);
              }
              if (visitedSubclassMirrors.add(superclassMirror) == false) {
                break;
              }
              superclassMirror = getSuperclassMirror(superclassMirror);
            }
          });
      subclasses
          .entrySet()
          .stream()
          .collect(
              Collectors.groupingBy(
                  it -> getPackageOf(it.getKey()),
                  Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)))
          .forEach((packageElement, blocks) -> writeSubclasses(packageElement, blocks));
    } else {
      processAnnotations(annotations, roundEnv);
    }
    return true;
  }

  private void writeSubclasses(
      PackageElement packageElement, Map<TypeMirror, SwitchSubclass> blocks) {
    Filer filer = processingEnv.getFiler();
    try {
      FileObject fileObject =
          filer.createSourceFile(
              (packageElement.isUnnamed() ? "" : packageElement.getQualifiedName() + ".")
                  + "GeneratedSubclass");
      try (final BufferedWriter w =
          new BufferedWriter(
              new OutputStreamWriter(fileObject.openOutputStream(), StandardCharsets.UTF_8))) {
        if (packageElement.isUnnamed() == false) {
          w.append(String.format("package %s;\n", packageElement.getQualifiedName()));
        }
        w.append(String.format("final class %s {\n", "GeneratedSubclass"));
        blocks.forEach((typeMirror, block) -> writeSubclass(w, typeMirror, block));
        w.append("}\n");
        w.flush();
      }
    } catch (final IOException e) {
      e.printStackTrace();
    }
  }

  private void writeSubclass(
      final BufferedWriter w, TypeMirror typeMirror, SwitchSubclass subclass) {
    try {
      w.append(
          String.format(
              "static final class %s extends %s {\n",
              asTypeElement(typeMirror).getSimpleName(),
              asTypeElement(typeMirror).getQualifiedName()));
      w.append("@Override\n");
      final ExecutableElement method =
          (ExecutableElement) subclass.switchParameter.getEnclosingElement();
      w.append(
          String.format(
              "%s%s %s(%s) {\n", // TODO throws
              method
                  .getModifiers()
                  .stream()
                  .filter(it -> it != Modifier.ABSTRACT)
                  .map(it -> it.toString())
                  .collect(Collectors.joining(" ", "", " ")),
              method.getReturnType().getKind() == TypeKind.VOID
                  ? "void"
                  : asTypeElement(method.getReturnType()).getQualifiedName(),
              method.getSimpleName(),
              method
                  .getParameters()
                  .stream()
                  .map(
                      it ->
                          String.format(
                              "final %s %s",
                              asTypeElement(it.asType()).getQualifiedName(), it.getSimpleName()))
                  .collect(Collectors.joining(", "))));
      w.append(
          subclass
              .caseParameters
              .stream()
              .map(
                  it ->
                      String.format(
                          "if (%s instanceof %s) {\n%s(%s);\n}",
                          subclass.switchParameter.getSimpleName(),
                          asTypeElement(it.asType()).getQualifiedName(),
                          ((ExecutableElement) it.getEnclosingElement()).getSimpleName(),
                          method
                              .getParameters()
                              .stream()
                              .map(
                                  it2 ->
                                      it2 == subclass.switchParameter
                                          ? String.format(
                                              "(%s) %s",
                                              asTypeElement(it.asType()).getQualifiedName(),
                                              it2.getSimpleName())
                                          : it2.getSimpleName())
                              .map(Object::toString)
                              .collect(Collectors.joining(", "))))
              .collect(Collectors.joining(" else ", "", "\n")));
      w.append("}\n");
      w.append("}\n");
    } catch (final IOException e) {
      e.printStackTrace();
    }
  }

  private PackageElement getPackageOf(TypeMirror typeMirror) {
    return processingEnv
        .getElementUtils()
        .getPackageOf(processingEnv.getTypeUtils().asElement(typeMirror));
  }

  private static final class SwitchSubclass {
    private VariableElement switchParameter;
    private Set<VariableElement> caseParameters;

    public SwitchSubclass() {
      this.caseParameters = new HashSet<>();
    }

    public void addSwitchParameter(final VariableElement switchParameter) {
      if (switchParameter == null) {
        return;
      }
      assert this.switchParameter == null;
      this.switchParameter = switchParameter;
    }

    public void addCaseParameter(final VariableElement caseParameter) {
      this.caseParameters.add(caseParameter);
    }

    public void drainTo(final SwitchSubclass other) {
      other.addSwitchParameter(switchParameter);
      for (final VariableElement caseParameter : caseParameters) {
        other.addCaseParameter(caseParameter);
      }
    }

    @Override
    public String toString() {
      return String.format(
          "%s: %s %s => %s",
          switchParameter == null
              ? ""
              : switchParameter.getEnclosingElement().getEnclosingElement().asType(),
          switchParameter == null ? "" : switchParameter.getEnclosingElement().asType(),
          switchParameter,
          caseParameters
              .stream()
              .map(
                  it ->
                      String.format(
                          "%s: %s %s",
                          it.getEnclosingElement().getEnclosingElement().asType(),
                          it.getEnclosingElement().asType(),
                          it))
              .collect(Collectors.joining(", ", "[", "]")));
    }
  }

  private void processAnnotations(
      Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    log(annotations.toString());
    final Set<? extends Element> switchElements = roundEnv.getElementsAnnotatedWith(Switch.class);
    log(switchElements.toString());
    for (final Element e : switchElements) {
      final VariableElement switchParameter = getSuitableSwitchParameter((VariableElement) e);
      if (switchParameter == null) {
        continue;
      }
      final TypeElement typeElement = getEnclosingTypeElement(switchParameter);
      final SwitchSubclass subclass =
          subclasses.computeIfAbsent(typeElement.asType(), key -> new SwitchSubclass());
      subclass.addSwitchParameter(switchParameter);
    }
    final Set<? extends Element> caseElements = roundEnv.getElementsAnnotatedWith(Case.class);
    log(caseElements.toString());
    for (final Element e : caseElements) {
      final VariableElement caseParameter = getSuitableCaseParameter((VariableElement) e);
      if (caseParameter == null) {
        continue;
      }
      final TypeElement typeElement = getEnclosingTypeElement(caseParameter);
      final SwitchSubclass subclass =
          subclasses.computeIfAbsent(typeElement.asType(), key -> new SwitchSubclass());
      subclass.addCaseParameter(caseParameter);
    }
  }

  private VariableElement getSuitableSwitchParameter(VariableElement parameter) {
    final ExecutableElement methodElement = (ExecutableElement) parameter.getEnclosingElement();
    final int parameterIndex = methodElement.getParameters().indexOf(parameter);
    TypeElement typeElement = getEnclosingTypeElement(methodElement);
    if (isAbstractElement(typeElement) && isAbstractElement(methodElement)) {
      return parameter;
    }
    final ExecutableElement superMethod =
        findOverridableMethod(
            methodElement,
            it -> isAbstractElement(it) && isAbstractElement(getEnclosingTypeElement(it)));
    if (superMethod == null) {
      log(
          String.format(
              "%s (featuring a Switch annotation on %s) is not overridable",
              typeElement, methodElement));
      return null;
    }
    return superMethod.getParameters().get(parameterIndex);
  }

  private VariableElement getSuitableCaseParameter(VariableElement parameter) {
    final ExecutableElement methodElement = (ExecutableElement) parameter.getEnclosingElement();
    final int parameterIndex = methodElement.getParameters().indexOf(parameter);
    final TypeElement typeElement = getEnclosingTypeElement(methodElement);
    if (isAbstractElement(typeElement)) {
      return parameter;
    }
    final ExecutableElement superMethod =
        findOverridableMethod(methodElement, it -> isAbstractElement(getEnclosingTypeElement(it)));
    if (superMethod == null) {
      log(
          String.format(
              "%s (featuring a Case annotation on %s) is not overridable",
              typeElement, methodElement));
      return null;
    }
    return superMethod.getParameters().get(parameterIndex);
  }

  private ExecutableElement findOverridableMethod(
      ExecutableElement overrider, Predicate<ExecutableElement> methodPredicate) {
    TypeElement classElement = getEnclosingTypeElement(overrider);
    while ((classElement = asTypeElement(getSuperclassMirror(classElement.asType()))) != null) {
      for (final Element method : classElement.getEnclosedElements()) {
        if (method instanceof ExecutableElement == false) {
          continue;
        }
        if (this.processingEnv
                .getElementUtils()
                .overrides(overrider, (ExecutableElement) method, classElement)
            == false) {
          continue;
        }
        if (methodPredicate.test((ExecutableElement) method)) {
          return (ExecutableElement) method;
        }
      }
    }
    return null;
  }

  private TypeElement getEnclosingTypeElement(Element element) {
    Element enclosingElement = element;
    do {
      enclosingElement = enclosingElement.getEnclosingElement();
      if (enclosingElement == null) {
        throw new IllegalStateException("No classes are enclosing an " + element);
      }
    } while (enclosingElement instanceof TypeElement == false);
    return (TypeElement) enclosingElement;
  }

  private boolean isAbstractElement(final Element method) {
    return method.getModifiers().contains(Modifier.ABSTRACT);
  }

  private TypeElement asTypeElement(final TypeMirror type) {
    if (type == null || type instanceof DeclaredType == false) {
      return null;
    }
    final DeclaredType declaredType = (DeclaredType) type;
    return (TypeElement) declaredType.asElement();
  }

  private TypeMirror getSuperclassMirror(TypeMirror type) {
    final List<? extends TypeMirror> superTypes =
        processingEnv.getTypeUtils().directSupertypes(type);
    if (superTypes.isEmpty()) {
      return null;
    }
    final TypeElement typeElement = asTypeElement(superTypes.get(0));
    if (typeElement != null
        && typeElement.getKind() == ElementKind.CLASS
        && typeElement.getModifiers().contains(Modifier.ABSTRACT)) {
      return typeElement.asType();
    }
    return null;
  }

  private void log(String msg) {
    if (processingEnv.getOptions().containsKey("debug")) {
      processingEnv.getMessager().printMessage(Kind.NOTE, msg);
    }
  }

  private void error(String msg, Element element, AnnotationMirror annotation) {
    processingEnv.getMessager().printMessage(Kind.ERROR, msg, element, annotation);
  }

  private void fatalError(String msg) {
    processingEnv.getMessager().printMessage(Kind.ERROR, "FATAL ERROR: " + msg);
  }
}
