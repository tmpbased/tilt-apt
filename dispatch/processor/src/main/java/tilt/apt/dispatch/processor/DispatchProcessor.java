package tilt.apt.dispatch.processor;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
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
import javax.lang.model.element.ModuleElement;
import javax.lang.model.element.Name;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ErrorType;
import javax.lang.model.type.NoType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.util.ElementKindVisitor9;
import javax.lang.model.util.SimpleElementVisitor9;
import javax.lang.model.util.SimpleTypeVisitor9;
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

  private final class AnnotatedClass {
    private final TypeElement typeElement;

    public AnnotatedClass(final TypeElement typeElement) {
      this.typeElement = typeElement;
    }

    FileObject createSourceFile(final String suffix) throws IOException {
      Filer filer = processingEnv.getFiler();
      return filer.createSourceFile(typeElement.getQualifiedName() + suffix);
    }

    void writePackage(Writer w) throws IOException {
      final PackageElement packageElement =
          processingEnv.getElementUtils().getPackageOf(typeElement);
      if (packageElement.isUnnamed() == false) {
        w.append(String.format("package %s;\n", packageElement.getQualifiedName()));
      }
    }

    TypeMirror getSuperclass() {
      return DispatchProcessor.this.getSuperclass(typeElement);
    }

    String getGeneratedSuperclassSimpleName() {
      return typeElement.getSimpleName() + SUFFIX_SUPERCLASS;
    }

    String getGeneratedSubclassSimpleName() {
      return typeElement.getSimpleName() + SUFFIX_SUBCLASS;
    }

    List<? extends TypeParameterElement> getTypeParameterElements() {
      return typeElement.getTypeParameters();
    }

    void writeClassTypeVariables(Writer w, int stubCount, String prefix, String suffix)
        throws IOException {
      final List<? extends TypeParameterElement> typeParameterElements = getTypeParameterElements();
      if (typeParameterElements.isEmpty()) {
        return;
      }
      w.append(
          Stream.concat(
                  typeParameterElements
                      .stream()
                      .map(Element::getSimpleName)
                      .map(Object::toString), // TODO + bounds
                  IntStream.range(typeParameterElements.size(), stubCount)
                      .mapToObj(it -> "Stub_" + it))
              .collect(Collectors.joining(", ", prefix + "<", ">" + suffix)));
    }

    List<? extends ExecutableElement> getAccessibleConstructors() {
      final List<ExecutableElement> constructors = new ArrayList<>();
      for (final Element element : typeElement.getEnclosedElements()) {
        final ExecutableElement constructor =
            element.accept(
                new ElementKindVisitor9<ExecutableElement, TypeElement>() {
                  @Override
                  public ExecutableElement visitExecutableAsConstructor(
                      ExecutableElement e, TypeElement p) {
                    if (e.getModifiers().contains(Modifier.PRIVATE)) {
                      return super.visitExecutableAsConstructor(e, p);
                    }
                    return e;
                  }
                },
                typeElement);
        if (constructor != null) {
          constructors.add(constructor);
        }
      }
      return constructors;
    }

    void startBlock(Writer w) throws IOException {
      w.append(" {\n");
    }

    void endBlock(Writer w) throws IOException {
      w.append("}\n");
    }

    private String formatMethodParameter(VariableElement variableElement) {
      return String.format(
          "%s %s",
          variableElement.asType().accept(new TypeNameVisitor(emptyName()), null),
          variableElement.getSimpleName());
    }

    String formatMethodParameters(final ExecutableElement executableElement) {
      return executableElement
          .getParameters()
          .stream()
          .map(this::formatMethodParameter)
          .collect(Collectors.joining(", "));
    }

    String formatMethodArguments(final ExecutableElement executableElement) {
      return executableElement
          .getParameters()
          .stream()
          .map(VariableElement::getSimpleName)
          .map(Object::toString)
          .collect(Collectors.joining(", "));
    }

    String formatMethodThrows(
        final ExecutableElement executableElement, final String prefix, final String suffix) {
      if (executableElement.getThrownTypes().isEmpty()) {
        return "";
      }
      return executableElement
          .getThrownTypes()
          .stream()
          .map(it -> it.accept(new TypeNameVisitor(emptyName()), null))
          .map(Objects::toString)
          .collect(Collectors.joining(", ", prefix + "throws ", suffix));
    }
  }

  final class TypeNameVisitor extends SimpleTypeVisitor9<Name, Void> {
    public TypeNameVisitor(final Name defaultValue) {
      super(defaultValue);
    }

    @Override
    public Name visitDeclared(DeclaredType t, Void p) {
      return asElement(t).getQualifiedName();
    }

    @Override
    public Name visitTypeVariable(TypeVariable t, Void p) {
      return asElement(t).getSimpleName();
    }
  }

  final class TypeVariableNames {
    private final DeclaredType declaredType;

    public TypeVariableNames(final DeclaredType declaredType) {
      this.declaredType = declaredType;
    }

    Stream<String> streamQualified() {
      final TypeNameVisitor typeVisitor = new TypeNameVisitor(emptyName());
      return declaredType
          .getTypeArguments()
          .stream()
          .map(it -> it.accept(typeVisitor, null))
          .map(Objects::toString);
    }
  }

  final class GeneratedSuperclass {
    private final AnnotatedClass ann;
    private final DeclaredType declaredType;

    public GeneratedSuperclass(final AnnotatedClass ann) {
      this.ann = ann;
      this.declaredType = asDeclaredType(ann.getSuperclass());
    }

    boolean exists() {
      return declaredType != null
          && Objects.equals(getSimpleName(declaredType), ann.getGeneratedSuperclassSimpleName());
    }

    private void ensureExists() {
      if (exists() == false) {
        throw new IllegalStateException("GeneratedSuperclass::exists() == false");
      }
    }

    void write(Writer w) throws IOException {
      ensureExists();
      w.append(String.format("abstract class %s", ann.getGeneratedSuperclassSimpleName()));
      writeClassTypeVariables(w, declaredType.getTypeArguments().size(), "", "");
      writeExtends(w);
      ann.startBlock(w);
      for (final ExecutableElement constructor : ann.getAccessibleConstructors()) {
        w.append(
            Stream.concat(Stream.of(Modifier.STATIC), constructor.getModifiers().stream())
                .map(Modifier::toString)
                .collect(Collectors.joining(" ", "", " ")));
        writeFactoryTypeVariables(w, "", " "); // TODO + constructor's type variables
        w.append(ann.typeElement.getQualifiedName());
        writeFactoryTypeVariables(w, "", "");
        w.append(
            String.format(
                " newInstance(%s)%s",
                ann.formatMethodParameters(constructor),
                ann.formatMethodThrows(constructor, " ", "")));
        ann.startBlock(w);
        w.append("return new ");
        w.append(ann.getGeneratedSubclassSimpleName());
        writeFactoryTypeVariables(w, "", ""); // TODO + constructor's type variables
        w.append(String.format("(%s);\n", ann.formatMethodArguments(constructor)));
        ann.endBlock(w);
      }
      ann.endBlock(w);
    }

    private void writeFactoryTypeVariables(Writer w, String prefix, String suffix)
        throws IOException {
      ensureExists();
      final List<? extends TypeParameterElement> typeParameterElements =
          ann.getTypeParameterElements();
      if (typeParameterElements.isEmpty()) {
        return;
      }
      w.append(
          typeParameterElements
              .stream()
              .map(Element::getSimpleName) // TODO + bounds
              .map(Object::toString)
              .collect(Collectors.joining(", ", prefix + "<", ">" + suffix)));
    }

    private void writeClassTypeVariables(Writer w, int stubCount, String prefix, String suffix)
        throws IOException {
      ensureExists();
      ann.writeClassTypeVariables(w, stubCount, prefix, suffix);
    }

    TypeMirror getSuperclass() {
      return declaredType.accept(new ExistingSuperclassTypeVisitor(), asElement(declaredType));
    }

    ExistingSuperclass getExistingSuperclass() {
      ensureExists();
      return new ExistingSuperclass(this);
    }

    private void writeExtends(Writer w) throws IOException {
      final ExistingSuperclass superclass = getExistingSuperclass();
      if (superclass.exists()) {
        w.write(" extends ");
        superclass.writeExtends(w);
      }
    }
  }

  final class ExistingSuperclass {
    private final DeclaredType declaredType;

    public ExistingSuperclass(final GeneratedSuperclass superGen) {
      this.declaredType = asDeclaredType(superGen.getSuperclass());
    }

    public boolean exists() {
      return declaredType != null;
    }

    private void ensureExists() {
      if (exists() == false) {
        throw new IllegalStateException("ExistingSuperclass::exists() == false");
      }
    }

    void writeExtends(Writer w) throws IOException {
      ensureExists();
      final TypeVariableNames names = new TypeVariableNames(declaredType);
      w.append(
          String.format(
              "%s%s",
              asElement(declaredType).getQualifiedName(),
              names.streamQualified().collect(Collectors.joining(", ", "<", ">"))));
    }
  }

  private void writeSuperclass(TypeElement typeElement) {
    final AnnotatedClass ann = new AnnotatedClass(typeElement);
    final GeneratedSuperclass superGen = new GeneratedSuperclass(ann);
    if (superGen.exists() == false) {
      return;
    }
    try {
      final FileObject fileObject = ann.createSourceFile(SUFFIX_SUPERCLASS);
      try (final BufferedWriter w =
          new BufferedWriter(
              new OutputStreamWriter(fileObject.openOutputStream(), StandardCharsets.UTF_8))) {
        ann.writePackage(w);
        superGen.write(w);
        w.flush();
      }
    } catch (final IOException e) {
      e.printStackTrace();
    }
  }

  final class GeneratedSubclass {
    private final AnnotatedClass ann;
    private final SwitchBlock block;

    public GeneratedSubclass(final AnnotatedClass ann, final SwitchBlock block) {
      this.ann = ann;
      this.block = block;
    }

    void write(Writer w) throws IOException {
      w.append(String.format("final class %s", ann.getGeneratedSubclassSimpleName()));
      writeClassTypeVariables(w, "", "");
      writeExtends(w);
      ann.startBlock(w);
      for (final ExecutableElement constructor : ann.getAccessibleConstructors()) {
        w.append(
            constructor
                .getModifiers()
                .stream()
                .map(Modifier::toString)
                .collect(Collectors.joining(" ", "", " ")));
        // TODO constructor's type variables
        w.append(ann.getGeneratedSubclassSimpleName());
        w.append(
            String.format(
                "(%s)%s",
                ann.formatMethodParameters(constructor),
                ann.formatMethodThrows(constructor, " ", "")));
        ann.startBlock(w);
        w.append(String.format("super(%s);\n", ann.formatMethodArguments(constructor)));
        ann.endBlock(w);
      }
      w.append("@Override\n");
      final ExecutableElement method = block.switchParameter.methodInType.methodElement;
      w.append(
          String.format(
              "%s%s %s(%s)", // TODO type variables & throws
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
                  .map(it -> ann.formatMethodParameters(method))
                  .collect(Collectors.joining(", "))));
      ann.startBlock(w);
      w.append(
          block
              .caseParameters
              .stream()
              .map(
                  it ->
                      String.format(
                          "if (%s instanceof %s) {\n%s(%s);\n}",
                          block.switchParameter.variableElement.getSimpleName(),
                          asTypeElement(it.variableElement.asType()).getQualifiedName(),
                          it.methodInType.methodElement.getSimpleName(),
                          method
                              .getParameters()
                              .stream()
                              .map(
                                  it2 ->
                                      it2 == block.switchParameter.variableElement
                                          ? String.format(
                                              "(%s) %s",
                                              asTypeElement(it.variableElement.asType())
                                                  .getQualifiedName(),
                                              it2.getSimpleName())
                                          : it2.getSimpleName())
                              .map(Object::toString)
                              .collect(Collectors.joining(", "))))
              .collect(Collectors.joining(" else ", "", "\n")));
      ann.endBlock(w);
      ann.endBlock(w);
    }

    private void writeClassTypeVariables(Writer w, String prefix, String suffix)
        throws IOException {
      ann.writeClassTypeVariables(w, 0, prefix, suffix);
    }

    private void writeExtends(Writer w) throws IOException {
      w.write(" extends ");
      w.append(ann.typeElement.getQualifiedName());
      writeClassTypeVariables(w, "", "");
    }
  }

  private void writeSubclass(TypeElement typeElement, SwitchBlock block) {
    final AnnotatedClass ann = new AnnotatedClass(typeElement);
    final GeneratedSubclass subGen = new GeneratedSubclass(ann, block);
    try {
      final FileObject fileObject = ann.createSourceFile(SUFFIX_SUBCLASS);
      try (final BufferedWriter w =
          new BufferedWriter(
              new OutputStreamWriter(fileObject.openOutputStream(), StandardCharsets.UTF_8))) {
        ann.writePackage(w);
        subGen.write(w);
        w.flush();
      }
    } catch (final IOException e) {
      e.printStackTrace();
    }
  }

  private static final class SwitchBlock {
    private final ParameterInMethod switchParameter;
    private final Set<ParameterInMethod> caseParameters;

    public SwitchBlock() {
      this(null);
    }

    public SwitchBlock(final ParameterInMethod switchParameter) {
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

    @Override
    public String toString() {
      return String.format("%s => %s", switchParameter, caseParameters);
    }
  }

  private void processAnnotations(
      Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    final Map<TypeElement, SwitchBlock> blocks = new HashMap<>();
    log(annotations.toString());
    final Set<? extends Element> switchElements = roundEnv.getElementsAnnotatedWith(Switch.class);
    log(switchElements.toString());
    for (final Element e : switchElements) {
      final ParameterInMethod switchParameter = new ParameterInMethod((VariableElement) e);
      if (switchParameter.isGoodSwitch() == false) {
        continue;
      }
      final TypeElement typeElement = switchParameter.methodInType.typeElement;
      if (blocks.remove(typeElement) != null) {
        fatalError("Limitation: no more than one switch per class is allowed");
        continue;
      }
      blocks.put(typeElement, new SwitchBlock(switchParameter));
    }
    final Set<? extends Element> caseElements = roundEnv.getElementsAnnotatedWith(Case.class);
    log(caseElements.toString());
    for (final Element e : caseElements) {
      final ParameterInMethod caseParameter =
          new ParameterInMethod((VariableElement) e).getGoodCase();
      if (caseParameter.isGoodCase() == false) {
        continue;
      }
      final TypeElement typeElement = caseParameter.methodInType.typeElement;
      final SwitchBlock block = blocks.computeIfAbsent(typeElement, key -> new SwitchBlock());
      block.addCaseParameter(caseParameter);
    }
    final var visitedElements = new HashSet<TypeElement>();
    for (final Map.Entry<TypeElement, SwitchBlock> e : blocks.entrySet()) {
      final TypeElement typeElement = e.getKey();
      final SwitchBlock block = e.getValue();
      if (visitedElements.contains(typeElement) || block.switchParameter == null) {
        continue;
      }
      final var visitedSuperBlocks = new LinkedList<SwitchBlock>();
      visitedSuperBlocks.add(block);
      getExistingSuperclass(typeElement)
          .accept(
              new SimpleTypeVisitor9<Void, TypeElement>() {
                @Override
                public Void visitDeclared(DeclaredType t, TypeElement p) {
                  final TypeElement typeElement = asElement(t);
                  final SwitchBlock superBlock = blocks.get(typeElement);
                  if (superBlock != null) {
                    visitedSuperBlocks.forEach(it -> superBlock.copyCaseParametersTo(it));
                    visitedSuperBlocks.push(superBlock);
                  }
                  if (visitedElements.add(typeElement) == false) {
                    return super.visitDeclared(t, p);
                  }
                  return getExistingSuperclass(typeElement).accept(this, p);
                }
              },
              typeElement);
    }
    blocks.values().removeIf(it -> it.switchParameter == null);
    blocks.forEach(
        (typeElement, subclass) -> {
          writeSuperclass(typeElement);
          writeSubclass(typeElement, subclass);
        });
  }

  private final class ParameterInMethod {
    final MethodInType methodInType;
    final VariableElement variableElement;

    ParameterInMethod(final VariableElement variableElement) {
      this.methodInType =
          new MethodInType((ExecutableElement) variableElement.getEnclosingElement());
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

    private boolean isGoodSwitch() {
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
      final MethodInType overridable = methodInType.findOverridable(this::isGoodCase);
      final int parameterIndex = methodInType.getMethodParameters().indexOf(variableElement);
      final VariableElement overridableVariableElement =
          overridable.getMethodParameters().get(parameterIndex);
      return new ParameterInMethod(overridable, overridableVariableElement);
    }

    @Override
    public String toString() {
      return String.format("%s(%s)", methodInType, variableElement);
    }
  }

  private TypeElement asElement(DeclaredType t) {
    return (TypeElement) t.asElement();
  }

  private TypeParameterElement asElement(TypeVariable t) {
    return (TypeParameterElement) t.asElement();
  }

  private final class MethodInType {
    final TypeElement typeElement;
    final ExecutableElement methodElement;

    MethodInType(final ExecutableElement methodElement) {
      this(getEnclosingTypeElement(methodElement), methodElement);
    }

    MethodInType(final TypeElement typeElement, final ExecutableElement methodElement) {
      this.typeElement = typeElement;
      this.methodElement = methodElement;
    }

    List<? extends VariableElement> getMethodParameters() {
      return methodElement.getParameters();
    }

    private class MethodVisitor extends SimpleElementVisitor9<MethodInType, TypeElement> {
      private final Predicate<MethodInType> predicate;

      public MethodVisitor(final Predicate<MethodInType> predicate) {
        super(MethodInType.this);
        this.predicate = predicate;
      }

      @Override
      public MethodInType visitExecutable(ExecutableElement e, TypeElement p) {
        if (processingEnv.getElementUtils().overrides(DEFAULT_VALUE.methodElement, e, p)) {
          final MethodInType result = new MethodInType(p, e);
          if (predicate.test(result)) {
            return result;
          }
        }
        return null;
      }
    }

    MethodInType findOverridable(Predicate<MethodInType> predicate) {
      final MethodVisitor methodVisitor = new MethodVisitor(predicate);
      return getExistingSuperclass(typeElement)
          .accept(
              new SimpleTypeVisitor9<>(this) {
                @Override
                public MethodInType visitDeclared(DeclaredType declaredType, MethodInType p) {
                  final TypeElement typeElement = asElement(declaredType);
                  for (final Element element : typeElement.getEnclosedElements()) {
                    final MethodInType result = element.accept(methodVisitor, typeElement);
                    if (result != null) {
                      return result;
                    }
                  }
                  return getExistingSuperclass(typeElement).accept(this, p);
                }
              },
              this);
    }

    @Override
    public String toString() {
      return String.format("%s::%s", typeElement, methodElement);
    }
  }

  private TypeElement getEnclosingTypeElement(final Element element) {
    final TypeElement typeElement =
        element.accept(
            new SimpleElementVisitor9<TypeElement, TypeElement>() {
              @Override
              public TypeElement visitModule(ModuleElement e, TypeElement p) {
                return p;
              }

              @Override
              public TypeElement visitPackage(PackageElement e, TypeElement p) {
                return p;
              }

              @Override
              public TypeElement visitType(TypeElement e, TypeElement p) {
                return e;
              }

              @Override
              protected TypeElement defaultAction(Element e, TypeElement p) {
                return e.getEnclosingElement().accept(this, p);
              }
            },
            null);
    if (typeElement == null) {
      throw new IllegalStateException("No classes are enclosing an " + element);
    }
    return typeElement;
  }

  private boolean isAbstractElement(final Element method) {
    return method.getModifiers().contains(Modifier.ABSTRACT);
  }

  private List<? extends TypeMirror> getTypeArguments(final TypeMirror typeMirror) {
    final DeclaredType declaredType = asDeclaredType(typeMirror);
    if (declaredType == null) {
      return Collections.emptyList();
    }
    return declaredType.getTypeArguments();
  }

  private String getSimpleName(final DeclaredType declaredType) {
    final TypeElement typeElement = asElement(declaredType);
    return typeElement.getSimpleName().toString();
  }

  private TypeElement asTypeElement(final TypeMirror typeMirror) {
    final DeclaredType declaredType = asDeclaredType(typeMirror);
    if (declaredType == null) {
      return null;
    }
    return (TypeElement) declaredType.asElement();
  }

  private DeclaredType asDeclaredType(final TypeMirror typeMirror) {
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

  /**
   * Returns the direct superclass of this type element. If this type element represents an
   * interface or the class java.lang.Object, then a NoType with kind NONE is returned.
   *
   * @return the direct superclass, or a NoType if there is none
   */
  private TypeMirror getSuperclass(final TypeElement typeElement) {
    return typeElement
        .getSuperclass()
        .accept(
            new SimpleTypeVisitor9<TypeMirror, TypeElement>(noneNoType()) {
              @Override
              public TypeMirror visitDeclared(DeclaredType t, TypeElement p) {
                return t;
              }

              @Override
              public TypeMirror visitError(ErrorType t, TypeElement p) {
                return visitDeclared(t, p);
              }
            },
            typeElement);
  }

  final class ExistingSuperclassTypeVisitor extends SimpleTypeVisitor9<TypeMirror, TypeElement> {
    public ExistingSuperclassTypeVisitor() {
      super(noneNoType());
    }

    @Override
    public TypeMirror visitDeclared(DeclaredType t, TypeElement subclassTypeElement) {
      if (getSimpleName(t).endsWith(SUFFIX_SUPERCLASS) && t.getTypeArguments().isEmpty() == false) {
        return t.getTypeArguments()
            .get(t.getTypeArguments().size() - 1)
            .accept(
                new SimpleTypeVisitor9<TypeMirror, TypeElement>(DEFAULT_VALUE) {
                  @Override
                  public TypeMirror visitDeclared(DeclaredType t, TypeElement subclassTypeElement) {
                    return t;
                  }
                },
                asElement(t));
      }
      return t;
    }

    @Override
    public TypeMirror visitError(ErrorType t, TypeElement p) {
      return visitDeclared(t, p);
    }
  }

  /**
   * Returns the direct superclass (that is not being generated by this processor) of this type
   * element. If this type element represents an interface or the class java.lang.Object, then a
   * NoType with kind NONE is returned.
   *
   * @return the direct superclass, or a NoType if there is none
   */
  private TypeMirror getExistingSuperclass(TypeElement typeElement) {
    return getSuperclass(typeElement).accept(new ExistingSuperclassTypeVisitor(), typeElement);
  }

  private Name emptyName() {
    return processingEnv.getElementUtils().getName("");
  }

  private NoType noneNoType() {
    return processingEnv.getTypeUtils().getNoType(TypeKind.NONE);
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
