package tilt.apt.dispatch.processor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.processing.Filer;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.tools.FileObject;

final class AnnotatedClass {
  private final Elements elements;
  private final TypeElement typeElement;

  public AnnotatedClass(final Elements elements, final TypeElement typeElement) {
    this.elements = elements;
    this.typeElement = typeElement;
  }

  FileObject createSourceFile(final Filer filer, final String suffix) throws IOException {
    final PackageElement packageElement = elements.getPackageOf(typeElement);
    return filer.createSourceFile(
        (packageElement.isUnnamed() ? "" : packageElement.getQualifiedName() + ".")
            + getClassSimpleName()
            + suffix);
  }

  void appendPackage(Appendable w) throws IOException {
    final PackageElement packageElement = elements.getPackageOf(typeElement);
    if (packageElement.isUnnamed() == false) {
      w.append(
          AnnotatedClass.formatStatement(
              String.format("package %s", packageElement.getQualifiedName())));
    }
  }

  TypeMirror getSuperclass() {
    return UnsafeOperations.getSuperclass(typeElement);
  }

  String getGeneratedSuperclassSimpleName() {
    return getClassSimpleName() + DispatchProcessor.SUFFIX_SUPERCLASS;
  }

  String getGeneratedSubclassSimpleName() {
    return getClassSimpleName() + DispatchProcessor.SUFFIX_SUBCLASS;
  }

  private String getClassSimpleName() {
    String name = elements.getBinaryName(typeElement).toString();
    final int lastIndex = name.lastIndexOf('.');
    if (lastIndex >= 0) {
      name = name.substring(lastIndex + 1);
    }
    return name;
  }

  List<? extends TypeParameterElement> getTypeParameterElements() {
    return typeElement.getTypeParameters();
  }

  Stream<String> typeNames(Stream<? extends Element> elements, TypeName visitor) {
    return elements
        .map(e -> e.asType().accept(visitor, new AppendableString()).toString())
        .filter(it -> it.isEmpty() == false);
  }

  String formatTypeParameterElements(
      List<? extends TypeParameterElement> typeParameterElements, TypeName visitor) {
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
        variableElement.asType().accept(TypeArgumentName.INSTANCE, new AppendableString()),
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
            .map(it -> it.accept(TypeArgumentName.INSTANCE, new AppendableString()).toString())
            .filter(it -> it.isEmpty() == false)
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
    return typeElement
        .asType()
        .accept(TypeArgumentName.INSTANCE, new AppendableString())
        .toString();
  }

  static String formatStatement(final String statement) {
    return statement + ";\n";
  }

  static String wrapIfNonBlank(final String value, final String prefix, final String suffix) {
    if (value.isBlank()) {
      return value;
    }
    return prefix + value + suffix;
  }
}
