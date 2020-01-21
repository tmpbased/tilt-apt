package tilt.apt.dispatch.processor;

import static tilt.apt.dispatch.processor.SafeCasts.asElement;
import static tilt.apt.dispatch.processor.UnsafeCasts.asDeclaredType;
import static tilt.apt.dispatch.processor.UnsafeCasts.asTypeElement;

import com.google.auto.service.AutoService;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
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
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ErrorType;
import javax.lang.model.type.NoType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVisitor;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.SimpleElementVisitor9;
import javax.lang.model.util.SimpleTypeVisitor9;
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

  private static <T> List<T> concat(final List<? extends T> a, final List<? extends T> b) {
    final List<T> c = new ArrayList<>(a.size() + b.size());
    c.addAll(a);
    c.addAll(b);
    return Collections.unmodifiableList(c);
  }

  private static String wrapIfNonBlank(
      final String value, final String prefix, final String suffix) {
    if (value.isBlank()) {
      return value;
    }
    return prefix + value + suffix;
  }

  private static String formatStatement(final String statement) {
    return statement + ";\n";
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

    void appendPackage(Appendable w) throws IOException {
      final PackageElement packageElement =
          processingEnv.getElementUtils().getPackageOf(typeElement);
      if (packageElement.isUnnamed() == false) {
        w.append(formatStatement(String.format("package %s", packageElement.getQualifiedName())));
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

    Stream<String> typeNames(Stream<? extends Element> elements, TypeVisitor<String, ?> visitor) {
      return elements.map(e -> e.asType().accept(visitor, null)).filter(Objects::nonNull);
    }

    String formatTypeParameterElements(
        List<? extends TypeParameterElement> typeParameterElements,
        TypeVisitor<String, ?> visitor) {
      if (typeParameterElements.isEmpty()) {
        return "";
      }
      return formatTypeParameterElements(
          typeNames(typeParameterElements.stream(), visitor).collect(Collectors.toList()));
    }

    String formatTypeParameterElements(List<? extends String> names) {
      if (names.isEmpty()) {
        return "";
      }
      return names.stream().collect(Collectors.joining(", ", "<", ">"));
    }

    List<? extends ExecutableElement> getAccessibleConstructors() {
      final List<ExecutableElement> constructors = new ArrayList<>();
      for (final ExecutableElement constructor :
          ElementFilter.constructorsIn(typeElement.getEnclosedElements())) {
        if (constructor.getModifiers().contains(Modifier.PRIVATE)) {
          continue;
        }
        constructors.add(constructor);
      }
      return constructors;
    }

    void startBlock(Appendable w) throws IOException {
      w.append(" {\n");
    }

    void endBlock(Appendable w) throws IOException {
      w.append("}\n");
    }

    private String formatMethodParameter(VariableElement variableElement) {
      return String.format(
          "%s %s",
          variableElement.asType().accept(ArgumentTypeNameVisitor.INSTANCE, null),
          variableElement.getSimpleName());
    }

    String formatMethodParameters(final ExecutableElement executableElement) {
      return executableElement
          .getParameters()
          .stream()
          .map(this::formatMethodParameter)
          .collect(Collectors.joining(", ", "(", ")"));
    }

    String formatMethodArguments(final ExecutableElement executableElement) {
      return executableElement
          .getParameters()
          .stream()
          .map(VariableElement::getSimpleName)
          .map(Object::toString)
          .collect(Collectors.joining(", ", "(", ")"));
    }

    String formatMethodThrows(final ExecutableElement executableElement) {
      if (executableElement.getThrownTypes().isEmpty()) {
        return "";
      }
      return "throws "
          + executableElement
              .getThrownTypes()
              .stream()
              .map(it -> it.accept(ArgumentTypeNameVisitor.INSTANCE, null))
              .filter(Objects::nonNull)
              .collect(Collectors.joining(", "));
    }

    String formatModifiers(Collection<Modifier> modifiers) {
      return formatModifiers(modifiers, it -> it);
    }

    String formatModifiers(Collection<Modifier> modifiers, UnaryOperator<Stream<Modifier>> op) {
      return op.apply(modifiers.stream())
          .distinct()
          .map(Modifier::toString)
          .collect(Collectors.joining(" "));
    }

    String getTypeName() {
      return typeElement.asType().accept(ArgumentTypeNameVisitor.INSTANCE, null);
    }
  }

  final class GeneratedSuperclass {
    private final AnnotatedClass ac;
    private final DeclaredType declaredType;

    public GeneratedSuperclass(final AnnotatedClass ann) {
      this.ac = ann;
      this.declaredType = asDeclaredType(ann.getSuperclass());
    }

    boolean exists() {
      return declaredType != null
          && Objects.equals(getSimpleName(declaredType), ac.getGeneratedSuperclassSimpleName());
    }

    private void ensureExists() {
      if (exists() == false) {
        throw new IllegalStateException("GeneratedSuperclass::exists() == false");
      }
    }

    void append(Appendable w) throws IOException {
      ensureExists();
      appendClassDecl(w);
      ac.startBlock(w);
      for (final ExecutableElement constructor : ac.getAccessibleConstructors()) {
        appendFactoryMethod(w, constructor);
      }
      ac.endBlock(w);
    }

    private void appendClassDecl(Appendable w) throws IOException {
      w.append(String.format("abstract class %s", ac.getGeneratedSuperclassSimpleName()));
      appendSuperclassTypeParameterElements(w);
      w.append(wrapIfNonBlank(formatExtends(), " ", ""));
    }

    private void appendSuperclassTypeParameterElements(Appendable w) throws IOException {
      final List<? extends TypeParameterElement> typeParameterElements =
          ac.getTypeParameterElements();
      final int stubCount = declaredType.getTypeArguments().size();
      if (typeParameterElements.isEmpty() && stubCount == 0) {
        return;
      }
      w.append(
          ac.formatTypeParameterElements(
              Stream.concat(
                      ac.typeNames(typeParameterElements.stream(), ParameterTypeNameVisitor.INSTANCE),
                      IntStream.range(typeParameterElements.size(), stubCount)
                          .mapToObj(it -> "Stub_" + it))
                  .collect(Collectors.toList())));
    }

    private void appendFactoryMethod(Appendable w, final ExecutableElement constructor)
        throws IOException {
      appendFactoryMethodDecl(w, constructor);
      ac.startBlock(w);
      appendFactoryMethodImpl(w, constructor);
      ac.endBlock(w);
    }

    private void appendFactoryMethodDecl(Appendable w, final ExecutableElement constructor)
        throws IOException {
      w.append(
          wrapIfNonBlank(
              ac.formatModifiers(
                  constructor.getModifiers(), it -> Stream.concat(Stream.of(Modifier.STATIC), it)),
              "",
              " "));
      w.append(
          wrapIfNonBlank(
              ac.formatTypeParameterElements(
                  concat(ac.getTypeParameterElements(), constructor.getTypeParameters()),
                  ParameterTypeNameVisitor.INSTANCE),
              "",
              " "));
      w.append(String.format("%s newInstance", ac.getTypeName()));
      w.append(ac.formatMethodParameters(constructor));
      w.append(wrapIfNonBlank(ac.formatMethodThrows(constructor), " ", ""));
    }

    private void appendFactoryMethodImpl(Appendable w, final ExecutableElement constructor)
        throws IOException {
      w.append(
          formatStatement(
              String.format(
                  "return new %s<>%s",
                  ac.getGeneratedSubclassSimpleName(), ac.formatMethodArguments(constructor))));
    }

    private String formatExtends() {
      final DeclaredType declaredType =
          asDeclaredType(
              this.declaredType.accept(
                  new ExistingSuperclassTypeVisitor(), asElement(this.declaredType)));
      if (declaredType != null) {
        return "extends " + declaredType.accept(ArgumentTypeNameVisitor.INSTANCE, null);
      }
      return "";
    }
  }

  private void writeSuperclass(TypeElement typeElement) {
    final AnnotatedClass ac = new AnnotatedClass(typeElement);
    final GeneratedSuperclass sg = new GeneratedSuperclass(ac);
    if (sg.exists() == false) {
      return;
    }
    try {
      final FileObject fileObject = ac.createSourceFile(SUFFIX_SUPERCLASS);
      try (final BufferedWriter w =
          new BufferedWriter(
              new OutputStreamWriter(fileObject.openOutputStream(), StandardCharsets.UTF_8))) {
        ac.appendPackage(w);
        sg.append(w);
        w.flush();
      }
    } catch (final IOException e) {
      e.printStackTrace();
    }
  }

  final class GeneratedSubclass {
    private final AnnotatedClass ac;
    private final SwitchBlock block;

    public GeneratedSubclass(final AnnotatedClass ann, final SwitchBlock block) {
      this.ac = ann;
      this.block = block;
    }

    void append(Appendable w) throws IOException {
      appendClassDecl(w);
      ac.startBlock(w);
      for (final ExecutableElement constructor : ac.getAccessibleConstructors()) {
        appendConstructor(w, constructor);
      }
      appendMethodImpl(w);
      ac.endBlock(w);
    }

    private void appendClassDecl(Appendable w) throws IOException {
      w.append(String.format("final class %s", ac.getGeneratedSubclassSimpleName()));
      w.append(
          ac.formatTypeParameterElements(
              ac.getTypeParameterElements(), ParameterTypeNameVisitor.INSTANCE));
      w.append(wrapIfNonBlank(formatExtends(), " ", ""));
    }

    private void appendConstructor(Appendable w, final ExecutableElement constructor)
        throws IOException {
      w.append(wrapIfNonBlank(ac.formatModifiers(constructor.getModifiers()), "", " "));
      w.append(
          wrapIfNonBlank(
              ac.formatTypeParameterElements(
                  constructor.getTypeParameters(), ParameterTypeNameVisitor.INSTANCE),
              "",
              " "));
      w.append(ac.getGeneratedSubclassSimpleName());
      w.append(ac.formatMethodParameters(constructor));
      w.append(wrapIfNonBlank(ac.formatMethodThrows(constructor), " ", ""));
      ac.startBlock(w);
      w.append(formatStatement(String.format("super%s", ac.formatMethodArguments(constructor))));
      ac.endBlock(w);
    }

    private void appendMethodImpl(Appendable w) throws IOException {
      w.append("@Override\n");
      final ExecutableElement method = block.switchParameter.methodInType.methodElement;
      w.append(
          wrapIfNonBlank(
              ac.formatModifiers(
                  method.getModifiers(), s -> s.filter(it -> it != Modifier.ABSTRACT)),
              "",
              " "));
      w.append(
          wrapIfNonBlank(
              ac.formatTypeParameterElements(
                  method.getTypeParameters(), ParameterTypeNameVisitor.INSTANCE),
              "",
              " "));
      w.append(
          String.format(
              "%s %s",
              method.getReturnType().accept(ArgumentTypeNameVisitor.INSTANCE, null),
              method.getSimpleName()));
      w.append(ac.formatMethodParameters(method));
      w.append(wrapIfNonBlank(ac.formatMethodThrows(method), " ", ""));
      ac.startBlock(w);
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
      ac.endBlock(w);
    }

    private String formatExtends() {
      return "extends " + ac.getTypeName();
    }
  }

  private void writeSubclass(SwitchBlock block) {
    final AnnotatedClass an = new AnnotatedClass(block.typeElement);
    final GeneratedSubclass gs = new GeneratedSubclass(an, block);
    try {
      final FileObject fileObject = an.createSourceFile(SUFFIX_SUBCLASS);
      try (final BufferedWriter w =
          new BufferedWriter(
              new OutputStreamWriter(fileObject.openOutputStream(), StandardCharsets.UTF_8))) {
        an.appendPackage(w);
        gs.append(w);
        w.flush();
      }
    } catch (final IOException e) {
      e.printStackTrace();
    }
  }

  private static final class SwitchBlock {
    private final TypeElement typeElement;
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

    @Override
    public String toString() {
      return String.format("%s => %s", switchParameter, caseParameters);
    }
  }

  private void processAnnotations(
      Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    final Map<String, SwitchBlock> blocks = new HashMap<>();
    log(annotations.toString());
    final Set<? extends Element> switchElements = roundEnv.getElementsAnnotatedWith(Switch.class);
    log(switchElements.toString());
    for (final Element e : switchElements) {
      final ParameterInMethod switchParameter = new ParameterInMethod((VariableElement) e);
      if (switchParameter.isGoodSwitch() == false) {
        continue;
      }
      final TypeElement typeElement = switchParameter.methodInType.typeElement;
      if (blocks.remove(typeElement.getQualifiedName().toString()) != null) {
        fatalError("Limitation: no more than one switch per class is allowed");
        continue;
      }
      blocks.put(
          typeElement.getQualifiedName().toString(), new SwitchBlock(typeElement, switchParameter));
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
      final SwitchBlock block =
          blocks.computeIfAbsent(
              typeElement.getQualifiedName().toString(), key -> new SwitchBlock(typeElement));
      block.addCaseParameter(caseParameter);
    }
    final var visitedElements = new HashSet<String>();
    for (final Map.Entry<String, SwitchBlock> e : blocks.entrySet()) {
      final String key = e.getKey();
      final SwitchBlock block = e.getValue();
      if (visitedElements.contains(key) || block.switchParameter == null) {
        continue;
      }
      final var visitedSuperBlocks = new LinkedList<SwitchBlock>();
      visitedSuperBlocks.add(block);
      getExistingSuperclass(block.typeElement)
          .accept(
              new SimpleTypeVisitor9<Void, TypeElement>() {
                @Override
                public Void visitDeclared(DeclaredType t, TypeElement p) {
                  final TypeElement typeElement = asElement(t);
                  final String key = typeElement.getQualifiedName().toString();
                  final SwitchBlock superBlock = blocks.get(key);
                  if (superBlock != null) {
                    visitedSuperBlocks.forEach(it -> superBlock.copyCaseParametersTo(it));
                    visitedSuperBlocks.push(superBlock);
                  }
                  if (visitedElements.add(key) == false) {
                    return super.visitDeclared(t, p);
                  }
                  return getExistingSuperclass(typeElement).accept(this, p);
                }
              },
              block.typeElement);
    }
    blocks.values().removeIf(it -> it.switchParameter == null);
    blocks.forEach(
        (key, block) -> {
          writeSuperclass(block.typeElement);
          writeSubclass(block);
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

  private String getSimpleName(final DeclaredType declaredType) {
    final TypeElement typeElement = asElement(declaredType);
    return typeElement.getSimpleName().toString();
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
