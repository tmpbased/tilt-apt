package tilt.apt.dispatch.processor;

import static tilt.apt.dispatch.processor.SafeOperations.asElement;
import static tilt.apt.dispatch.processor.UnsafeOperations.getExistingSuperclass;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.ModuleElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.SimpleElementVisitor9;
import javax.lang.model.util.SimpleTypeVisitor9;

final class MethodInType {
  static final class EnclosingTypeVisitor extends SimpleElementVisitor9<TypeElement, TypeElement> {
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
  }

  static TypeElement getEnclosingTypeElement(final Element element) {
    final TypeElement typeElement = element.accept(new EnclosingTypeVisitor(), null);
    if (typeElement == null) {
      throw new IllegalStateException("No classes are enclosing an " + element);
    }
    return typeElement;
  }

  private final Elements elements;
  final TypeElement typeElement;
  final ExecutableElement methodElement;

  MethodInType(final Elements elements, final ExecutableElement methodElement) {
    this(elements, getEnclosingTypeElement(methodElement), methodElement);
  }

  MethodInType(
      final Elements elements,
      final TypeElement typeElement,
      final ExecutableElement methodElement) {
    this.elements = elements;
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
      if (elements.overrides(DEFAULT_VALUE.methodElement, e, p)) {
        final MethodInType result = new MethodInType(elements, p, e);
        if (predicate.test(result)) {
          return result;
        }
      }
      return null;
    }
  }

  private class DeclaredTypeMethodVisitor extends SimpleTypeVisitor9<MethodInType, Void> {
    private final MethodVisitor methodVisitor;

    public DeclaredTypeMethodVisitor(final MethodVisitor methodVisitor) {
      this.methodVisitor = methodVisitor;
    }

    @Override
    public MethodInType visitDeclared(DeclaredType declaredType, Void p) {
      final TypeElement typeElement = asElement(declaredType);
      for (final Element element : typeElement.getEnclosedElements()) {
        final MethodInType result = element.accept(methodVisitor, typeElement);
        if (result != null) {
          return result;
        }
      }
      return Optional.ofNullable(getExistingSuperclass(typeElement))
          .map(it -> it.accept(this, p))
          .orElseGet(() -> defaultAction(declaredType, p));
    }
  }

  MethodInType findOverridable(Predicate<MethodInType> predicate) {
    final MethodVisitor methodVisitor = new MethodVisitor(predicate);
    return Optional.ofNullable(getExistingSuperclass(typeElement))
        .map(it -> it.accept(new DeclaredTypeMethodVisitor(methodVisitor), null))
        .orElse(this);
  }

  @Override
  public String toString() {
    return String.format("%s::%s", typeElement, methodElement);
  }
}
