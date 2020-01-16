package tilt.apt.dispatch.processor;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.tools.Diagnostic.Kind;
import javax.tools.FileObject;
import com.google.auto.service.AutoService;
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
  private static final String SUFFIX_SUBCLASS = "_GeneratedSubclass";
  private static final String SUFFIX_SUPERCLASS = "_GeneratedSuperclass";

  @Override
  public Set<String> getSupportedAnnotationTypes() {
    return Set.of(Switch.class.getName(), Case.class.getName());
  }

  @Override
  public SourceVersion getSupportedSourceVersion() {
    return SourceVersion.latestSupported();
  }

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
    if (roundEnv.processingOver() == false) {
      processAnnotations(annotations, roundEnv);
    }
    return true;
  }

  private void writeSuperclass(TypeMirror typeMirror) {
    Filer filer = processingEnv.getFiler();
    try {
      final TypeElement typeElement = asTypeElement(typeMirror);
      final FileObject fileObject =
          filer.createSourceFile(typeElement.getQualifiedName() + SUFFIX_SUPERCLASS);
      final PackageElement packageElement =
          processingEnv.getElementUtils().getPackageOf(typeElement);
      try (final BufferedWriter w =
          new BufferedWriter(
              new OutputStreamWriter(fileObject.openOutputStream(), StandardCharsets.UTF_8))) {
        if (packageElement.isUnnamed() == false) {
          w.append(String.format("package %s;\n", packageElement.getQualifiedName()));
        }
        w.append(
            String.format("abstract class %s%s", typeElement.getSimpleName(), SUFFIX_SUPERCLASS));
        final List<? extends Element> typeVariableElements =
            getTypeArguments(typeMirror)
                .stream()
                .filter(TypeVariable.class::isInstance)
                .map(TypeVariable.class::cast)
                .map(TypeVariable::asElement)
                .collect(Collectors.toList());
        if (Objects.equals(
            getSimpleName(typeElement.getSuperclass()),
            typeElement.getSimpleName() + SUFFIX_SUPERCLASS)) {
          final List<? extends TypeMirror> superclassTypeArguments =
              getTypeArguments(typeElement.getSuperclass());
          if (superclassTypeArguments.isEmpty() == false) {
            w.append(
                Stream.concat(
                        typeVariableElements
                            .stream()
                            .map(Element::getSimpleName)
                            .map(Object::toString),
                        IntStream.range(typeVariableElements.size(), superclassTypeArguments.size())
                            .mapToObj(it -> "G_" + it))
                    .collect(Collectors.joining(", ", "<", ">")));
            final TypeMirror superclassTypeMirror =
                superclassTypeArguments.get(superclassTypeArguments.size() - 1);
            if (getQualifiedName(superclassTypeMirror) != null) {
              w.append(
                  String.format(
                      " extends %s%s",
                      getQualifiedName(superclassTypeMirror),
                      getTypeArguments(superclassTypeMirror)
                          .stream()
                          .map(
                              it ->
                                  it instanceof TypeVariable
                                      ? ((TypeVariable) it).asElement().getSimpleName()
                                      : getQualifiedName(it))
                          .map(Objects::toString)
                          .collect(Collectors.joining(", ", "<", ">"))));
            }
          }
        }
        w.append(" {\n");
        w.append(
            String.format(
                "public static %s%s%s newInstance() {\n", // TODO throws
                typeVariableElements
                    .stream()
                    .map(Element::getSimpleName)
                    .map(Object::toString)
                    .collect(Collectors.joining(", ", "<", "> ")),
                typeElement.getQualifiedName(),
                typeVariableElements
                    .stream()
                    .map(Element::getSimpleName)
                    .map(Object::toString)
                    .collect(Collectors.joining(", ", "<", ">"))));
        w.append(
            String.format(
                "return new %s%s%s();\n",
                typeElement.getSimpleName(),
                SUFFIX_SUBCLASS,
                typeVariableElements
                    .stream()
                    .map(Element::getSimpleName)
                    .map(Object::toString)
                    .collect(Collectors.joining(", ", "<", ">"))));
        w.append("}\n");
        w.append("}\n");
        w.flush();
      }
    } catch (final IOException e) {
      e.printStackTrace();
    }
  }

  private void writeSubclass(TypeMirror typeMirror, SwitchSubclass subclass) {
    Filer filer = processingEnv.getFiler();
    try {
      final TypeElement typeElement = asTypeElement(typeMirror);
      final FileObject fileObject =
          filer.createSourceFile(typeElement.getQualifiedName() + SUFFIX_SUBCLASS);
      final PackageElement packageElement =
          processingEnv.getElementUtils().getPackageOf(typeElement);
      try (final BufferedWriter w =
          new BufferedWriter(
              new OutputStreamWriter(fileObject.openOutputStream(), StandardCharsets.UTF_8))) {
        if (packageElement.isUnnamed() == false) {
          w.append(String.format("package %s;\n", packageElement.getQualifiedName()));
        }
        final String typeVariableNames =
            getTypeArguments(typeMirror)
                .stream()
                .filter(TypeVariable.class::isInstance)
                .map(TypeVariable.class::cast)
                .map(TypeVariable::asElement)
                .map(Element::getSimpleName)
                .map(Object::toString)
                .collect(Collectors.joining(", ", "<", ">"));
        w.append(
            String.format(
                "final class %s%s%s extends %s%s {\n",
                typeElement.getSimpleName(),
                SUFFIX_SUBCLASS,
                typeVariableNames,
                typeElement.getQualifiedName(),
                typeVariableNames));
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
        w.flush();
      }
    } catch (final IOException e) {
      e.printStackTrace();
    }
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
    final Map<TypeMirror, SwitchSubclass> subclasses = new HashMap<>();
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
            final SwitchSubclass superSubclass = subclasses.get(superclassMirror);
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
    subclasses.forEach(
        (typeMirror, subclass) -> {
          writeSuperclass(typeMirror);
          writeSubclass(typeMirror, subclass);
        });
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

  private DeclaredType asDeclaredType(final TypeMirror typeMirror) {
    if (typeMirror instanceof DeclaredType) {
      return (DeclaredType) typeMirror;
    }
    return null;
  }

  private List<? extends TypeMirror> getTypeArguments(final TypeMirror typeMirror) {
    final DeclaredType declaredType = asDeclaredType(typeMirror);
    if (declaredType == null) {
      return Collections.emptyList();
    }
    return declaredType.getTypeArguments();
  }

  private String getSimpleName(final TypeMirror typeMirror) {
    final TypeElement typeElement = asTypeElement(typeMirror);
    if (typeElement == null) {
      return null;
    }
    return typeElement.getSimpleName().toString();
  }

  private String getQualifiedName(final TypeMirror typeMirror) {
    final TypeElement typeElement = asTypeElement(typeMirror);
    if (typeElement == null) {
      return null;
    }
    return typeElement.getQualifiedName().toString();
  }

  private TypeElement asTypeElement(final TypeMirror typeMirror) {
    final DeclaredType declaredType = asDeclaredType(typeMirror);
    if (declaredType == null) {
      return null;
    }
    return (TypeElement) declaredType.asElement();
  }

  private TypeMirror getSuperclassMirror(TypeMirror type) {
    final DeclaredType declaredType = asDeclaredType(asTypeElement(type).getSuperclass());
    if (declaredType == null) {
      return null;
    }
    final TypeElement typeElement;
    if (declaredType.asElement().getSimpleName().toString().endsWith(SUFFIX_SUPERCLASS)
        && declaredType.getTypeArguments().isEmpty() == false) {
      typeElement =
          asTypeElement(
              declaredType.getTypeArguments().get(declaredType.getTypeArguments().size() - 1));
    } else {
      typeElement = (TypeElement) declaredType.asElement();
    }
    if (typeElement == null) {
      return null;
    }
    return typeElement.asType();
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
