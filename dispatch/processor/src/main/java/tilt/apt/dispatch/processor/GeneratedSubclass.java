package tilt.apt.dispatch.processor;

import static tilt.apt.dispatch.processor.AnnotatedClass.formatStatement;
import static tilt.apt.dispatch.processor.AnnotatedClass.wrapIfNonBlank;

import java.io.IOException;
import java.util.stream.Collectors;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;

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
        ac.formatTypeParameterElements(ac.getTypeParameterElements(), TypeParameterName.INSTANCE));
    w.append(wrapIfNonBlank(formatExtends(), " ", ""));
  }

  private void appendConstructor(Appendable w, final ExecutableElement constructor)
      throws IOException {
    w.append(wrapIfNonBlank(ac.formatModifiers(constructor.getModifiers()), "", " "));
    w.append(
        wrapIfNonBlank(
            ac.formatTypeParameterElements(
                constructor.getTypeParameters(), TypeParameterName.INSTANCE),
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
    final ExecutableElement method = block.getSwitchMethodElement();
    w.append(
        wrapIfNonBlank(
            ac.formatModifiers(method.getModifiers(), s -> s.filter(it -> it != Modifier.ABSTRACT)),
            "",
            " "));
    w.append(
        wrapIfNonBlank(
            ac.formatTypeParameterElements(method.getTypeParameters(), TypeParameterName.INSTANCE),
            "",
            " "));
    w.append(
        String.format(
            "%s %s",
            method.getReturnType().accept(TypeArgumentName.INSTANCE, new AppendableString()),
            method.getSimpleName()));
    w.append(ac.formatMethodParameters(method));
    w.append(wrapIfNonBlank(ac.formatMethodThrows(method), " ", ""));
    ac.startBlock(w);
    w.append(
        block
            .getCaseParameters()
            .stream()
            .map(
                it ->
                    String.format(
                        "if (%s instanceof %s) {\n%s(%s);\n}",
                        block.getSwitchMethodParameterName(),
                        it.getMethodParameterTypeName(),
                        it.getMethodName(),
                        method
                            .getParameters()
                            .stream()
                            .map(
                                it2 ->
                                    it2.getSimpleName().equals(block.getSwitchMethodParameterName())
                                        ? String.format(
                                            "(%s) %s",
                                            it.getMethodParameterTypeName(), it2.getSimpleName())
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
