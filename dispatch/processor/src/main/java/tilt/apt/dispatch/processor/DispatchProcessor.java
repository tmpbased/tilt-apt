package tilt.apt.dispatch.processor;

import com.google.auto.service.AutoService;
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
import javax.lang.model.type.IntersectionType;
import javax.lang.model.type.NoType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
import javax.lang.model.type.TypeVisitor;
import javax.lang.model.type.WildcardType;
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

    void writeSuperclassTypeParameterElements(Writer w, int stubCount, String prefix, String suffix)
        throws IOException {
      final List<? extends TypeParameterElement> typeParameterElements = getTypeParameterElements();
      if (typeParameterElements.isEmpty()) {
        return;
      }
      w.append(
          Stream.concat(
                  typeParameterElements
                      .stream()
                      .map(Element::asType)
                      .map(it -> it.accept(new TypeParameterNameVisitor(), null))
                      .filter(Objects::nonNull),
                  IntStream.range(typeParameterElements.size(), stubCount)
                      .mapToObj(it -> "Stub_" + it))
              .collect(Collectors.joining(", ", prefix + "<", ">" + suffix)));
    }

    void writeTypeParameterElements(
        Writer w,
        List<? extends TypeParameterElement> typeParameterElements,
        TypeVisitor<String, ?> visitor,
        String prefix,
        String suffix)
        throws IOException {
      if (typeParameterElements.isEmpty()) {
        return;
      }
      w.append(
          typeParameterElements
              .stream()
              .map(e -> e.asType().accept(visitor, null))
              .filter(Objects::nonNull)
              .collect(Collectors.joining(", ", prefix + "<", ">" + suffix)));
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

    void startBlock(Writer w) throws IOException {
      w.append(" {\n");
    }

    void endBlock(Writer w) throws IOException {
      w.append("}\n");
    }

    private String formatMethodParameter(VariableElement variableElement) {
      return String.format(
          "%s %s",
          variableElement.asType().accept(new TypeArgumentNameVisitor(), null),
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
          .map(it -> it.accept(new TypeArgumentNameVisitor(), null))
          .filter(Objects::nonNull)
          .collect(Collectors.joining(", ", prefix + "throws ", suffix));
    }
  }

  final class TypeArgumentNameVisitor extends SimpleTypeVisitor9<String, Void> {
    @Override
    public String visitDeclared(DeclaredType t, Void p) {
      return asElement(t).getQualifiedName()
          + (t.getTypeArguments().isEmpty()
              ? ""
              : t.getTypeArguments()
                  .stream()
                  .map(it -> it.accept(this, null))
                  .filter(Objects::nonNull)
                  .collect(Collectors.joining(", ", "<", ">")));
    }

    @Override
    public String visitTypeVariable(TypeVariable t, Void p) {
      return asElement(t).getSimpleName().toString();
    }

    @Override
    public String visitWildcard(WildcardType t, Void p) {
      return "?" + formatExtendsBound(t) + formatSuperBound(t);
    }

    private String formatExtendsBound(WildcardType t) {
      final TypeMirror bound = t.getExtendsBound();
      return bound == null ? "" : " extends " + bound.accept(this, null);
    }

    private String formatSuperBound(WildcardType t) {
      final TypeMirror bound = t.getSuperBound();
      return bound == null ? "" : " super " + bound.accept(this, null);
    }
  }

  final class TypeParameterNameVisitor extends SimpleTypeVisitor9<String, Void> {
    private final TypeArgumentNameVisitor argumentName;

    public TypeParameterNameVisitor() {
      argumentName = new TypeArgumentNameVisitor();
    }

    @Override
    public String visitDeclared(DeclaredType t, Void p) {
      return asElement(t).getQualifiedName() + getTypeArguments(t);
    }

    private String getTypeArguments(DeclaredType t) {
      final List<? extends TypeMirror> typeArguments = t.getTypeArguments();
      if (typeArguments.isEmpty()) {
        return DEFAULT_VALUE;
      }
      return t.getTypeArguments()
          .stream()
          .map(it -> it.accept(argumentName, null))
          .filter(Objects::nonNull)
          .collect(Collectors.joining(", ", "<", ">"));
    }

    @Override
    public String visitTypeVariable(TypeVariable t, Void p) {
      final String upperBoundName = t.getUpperBound().accept(this, null);
      return asElement(t).getSimpleName()
          + (upperBoundName == null || upperBoundName.equals("java.lang.Object")
              ? ""
              : " extends " + upperBoundName);
    }

    @Override
    public String visitIntersection(IntersectionType t, Void p) {
      return t.getBounds()
          .stream()
          .map(it -> it.accept(this, null))
          .filter(Objects::nonNull)
          .collect(Collectors.joining(" & "));
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

    void write(Writer w) throws IOException {
      ensureExists();
      writeClassDecl(w);
      ac.startBlock(w);
      for (final ExecutableElement constructor : ac.getAccessibleConstructors()) {
        writeFactoryMethodDecl(w, constructor);
        ac.startBlock(w);
        writeFactoryMethodImpl(w, constructor);
        ac.endBlock(w);
      }
      ac.endBlock(w);
    }

    private void writeClassDecl(Writer w) throws IOException {
      w.append(String.format("abstract class %s", ac.getGeneratedSuperclassSimpleName()));
      ac.writeSuperclassTypeParameterElements(w, declaredType.getTypeArguments().size(), "", "");
      writeExtends(w);
    }

    private void writeFactoryMethodDecl(Writer w, final ExecutableElement constructor)
        throws IOException {
      w.append(
          Stream.concat(Stream.of(Modifier.STATIC), constructor.getModifiers().stream())
              .distinct()
              .map(Modifier::toString)
              .collect(Collectors.joining(" ", "", " ")));
      ac.writeTypeParameterElements(
          w,
          Stream.of(constructor.getTypeParameters(), ac.getTypeParameterElements())
              .flatMap(List::stream)
              .collect(Collectors.toList()),
          new TypeParameterNameVisitor(),
          "",
          " ");
      w.append(ac.typeElement.getQualifiedName());
      ac.writeTypeParameterElements(
          w, ac.getTypeParameterElements(), new TypeArgumentNameVisitor(), "", "");
      w.append(
          String.format(
              " newInstance(%s)%s",
              ac.formatMethodParameters(constructor), ac.formatMethodThrows(constructor, " ", "")));
    }

    private void writeFactoryMethodImpl(Writer w, final ExecutableElement constructor)
        throws IOException {
      w.append("return new ");
      w.append(ac.getGeneratedSubclassSimpleName());
      w.append(String.format("<>(%s);\n", ac.formatMethodArguments(constructor)));
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
        final String name = superclass.getName();
        if (name != null) {
          w.write(" extends ");
          w.write(name);
        }
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

    String getName() throws IOException {
      ensureExists();
      return declaredType.accept(new TypeArgumentNameVisitor(), null);
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
        ac.writePackage(w);
        sg.write(w);
        w.flush();
      }
    } catch (final IOException e) {
      e.printStackTrace();
    }
  }

  final class GeneratedSubclass {
    private final AnnotatedClass an;
    private final SwitchBlock block;

    public GeneratedSubclass(final AnnotatedClass ann, final SwitchBlock block) {
      this.an = ann;
      this.block = block;
    }

    void write(Writer w) throws IOException {
      w.append(String.format("final class %s", an.getGeneratedSubclassSimpleName()));
      writeClassTypeVariables(w, "", "");
      writeExtends(w);
      an.startBlock(w);
      for (final ExecutableElement constructor : an.getAccessibleConstructors()) {
        w.append(
            constructor
                .getModifiers()
                .stream()
                .map(Modifier::toString)
                .collect(Collectors.joining(" ", "", " ")));
        an.writeTypeParameterElements(
            w, constructor.getTypeParameters(), new TypeParameterNameVisitor(), "", " ");
        w.append(an.getGeneratedSubclassSimpleName());
        w.append(
            String.format(
                "(%s)%s",
                an.formatMethodParameters(constructor),
                an.formatMethodThrows(constructor, " ", "")));
        an.startBlock(w);
        w.append(String.format("super(%s);\n", an.formatMethodArguments(constructor)));
        an.endBlock(w);
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
                  .map(it -> an.formatMethodParameters(method))
                  .collect(Collectors.joining(", "))));
      an.startBlock(w);
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
      an.endBlock(w);
      an.endBlock(w);
    }

    private void writeClassTypeVariables(Writer w, String prefix, String suffix)
        throws IOException {
      an.writeSuperclassTypeParameterElements(w, 0, prefix, suffix);
    }

    private void writeExtends(Writer w) throws IOException {
      w.write(" extends ");
      w.append(an.typeElement.getQualifiedName());
      writeClassTypeVariables(w, "", "");
    }
  }

  private void writeSubclass(TypeElement typeElement, SwitchBlock block) {
    final AnnotatedClass an = new AnnotatedClass(typeElement);
    final GeneratedSubclass gs = new GeneratedSubclass(an, block);
    try {
      final FileObject fileObject = an.createSourceFile(SUFFIX_SUBCLASS);
      try (final BufferedWriter w =
          new BufferedWriter(
              new OutputStreamWriter(fileObject.openOutputStream(), StandardCharsets.UTF_8))) {
        an.writePackage(w);
        gs.write(w);
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
