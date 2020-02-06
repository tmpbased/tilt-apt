package tilt.apt.dispatch.processor;

import static java.util.Optional.ofNullable;
import static tilt.apt.dispatch.processor.SafeOperations.getSimpleName;
import static tilt.apt.dispatch.processor.UnsafeOperations.asDeclaredType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.type.DeclaredType;
import tilt.apt.dispatch.processor.UnsafeOperations.ExistingSuperclassType;

final class GeneratedSuperclass {
  private static <T> List<T> concat(final List<? extends T> a, final List<? extends T> b) {
    final List<T> c = new ArrayList<>(a.size() + b.size());
    c.addAll(a);
    c.addAll(b);
    return Collections.unmodifiableList(c);
  }

  private final AnnotatedClass ac;
  private final DeclaredType declaredType;

  public GeneratedSuperclass(final AnnotatedClass ann) {
    this.ac = ann;
    this.declaredType = ofNullable(ann.getSuperclass()).map(it -> asDeclaredType(it)).orElse(null);
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
    w.append(AnnotatedClass.wrapIfNonBlank(formatExtends(), " ", ""));
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
                    ac.typeNames(typeParameterElements.stream(), TypeParameterName.INSTANCE),
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
        AnnotatedClass.wrapIfNonBlank(
            ac.formatModifiers(
                constructor.getModifiers(), it -> Stream.concat(Stream.of(Modifier.STATIC), it)),
            "",
            " "));
    w.append(
        AnnotatedClass.wrapIfNonBlank(
            ac.formatTypeParameterElements(
                concat(ac.getTypeParameterElements(), constructor.getTypeParameters()),
                TypeParameterName.INSTANCE),
            "",
            " "));
    w.append(String.format("%s newInstance", ac.getTypeName()));
    w.append(ac.formatMethodParameters(constructor));
    w.append(AnnotatedClass.wrapIfNonBlank(ac.formatMethodThrows(constructor), " ", ""));
  }

  private void appendFactoryMethodImpl(Appendable w, final ExecutableElement constructor)
      throws IOException {
    w.append(
        AnnotatedClass.formatStatement(
            String.format(
                "return new %s%s%s",
                ac.getGeneratedSubclassSimpleName(),
                constructor.getTypeParameters().isEmpty() ? "" : "<>",
                ac.formatMethodArguments(constructor))));
  }

  private String formatExtends() {
    return ofNullable(this.declaredType.accept(ExistingSuperclassType.INSTANCE, null))
        .map(it -> asDeclaredType(it))
        .map(it -> "extends " + it.accept(TypeArgumentName.INSTANCE, new AppendableString()))
        .orElse("");
  }
}
