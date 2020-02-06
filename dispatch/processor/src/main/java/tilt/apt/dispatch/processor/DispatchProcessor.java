package tilt.apt.dispatch.processor;

import static java.util.Optional.ofNullable;
import static tilt.apt.dispatch.processor.SafeOperations.asElement;
import static tilt.apt.dispatch.processor.SafeOperations.getQualifiedName;
import static tilt.apt.dispatch.processor.UnsafeOperations.getExistingSuperclass;

import com.google.auto.service.AutoService;
import com.google.googlejavaformat.java.Formatter;
import com.google.googlejavaformat.java.FormatterException;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
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
  static final String SUFFIX_SUBCLASS = "_GeneratedSubclass";
  static final String SUFFIX_SUPERCLASS = "_GeneratedSuperclass";

  static final boolean OPTION_INHERIT_CASES = false;

  public DispatchProcessor() {}

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

  private void writeSuperclass(TypeElement typeElement) {
    final AnnotatedClass ac = new AnnotatedClass(processingEnv.getElementUtils(), typeElement);
    final GeneratedSuperclass sg = new GeneratedSuperclass(ac);
    if (sg.exists() == false) {
      return;
    }
    try {
      final FileObject fileObject =
          ac.createSourceFile(processingEnv.getFiler(), SUFFIX_SUPERCLASS);
      try (final Writer w =
          new OutputStreamWriter(fileObject.openOutputStream(), StandardCharsets.UTF_8)) {
        final Appendable aw = new AppendableString();
        ac.appendPackage(aw);
        sg.append(aw);
        try {
          w.write(new Formatter().formatSource(aw.toString()));
        } catch (final FormatterException e) {
          System.out.println(aw.toString());
          throw e;
        }
        w.flush();
      }
    } catch (final IOException | FormatterException e) {
      e.printStackTrace();
    }
  }

  private void writeSubclass(SwitchBlock block) {
    final AnnotatedClass an =
        new AnnotatedClass(processingEnv.getElementUtils(), block.typeElement);
    final GeneratedSubclass gs = new GeneratedSubclass(an, block);
    try {
      final FileObject fileObject = an.createSourceFile(processingEnv.getFiler(), SUFFIX_SUBCLASS);
      try (final Writer w =
          new OutputStreamWriter(fileObject.openOutputStream(), StandardCharsets.UTF_8)) {
        final Appendable aw = new AppendableString();
        an.appendPackage(aw);
        gs.append(aw);
        try {
          w.write(new Formatter().formatSource(aw.toString()));
        } catch (final FormatterException e) {
          System.out.println(aw.toString());
          throw e;
        }
        w.flush();
      }
    } catch (final IOException | FormatterException e) {
      e.printStackTrace();
    }
  }

  private void processAnnotations(
      Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    final Map<String, SwitchBlock> blocks = new HashMap<>();
    log(annotations.toString());
    final Set<? extends Element> switchElements = roundEnv.getElementsAnnotatedWith(Switch.class);
    log(switchElements.toString());
    for (final Element e : switchElements) {
      final AnnotationMirror am = getAnnotationMirror(e, Switch.class);
      final ParameterInMethod switchParameter =
          new ParameterInMethod(processingEnv.getElementUtils(), (VariableElement) e);
      if (switchParameter.isGoodSwitch() == false) {
        error(
            "Method with @Switch is not overridable",
            switchParameter.methodInType.methodElement,
            am);
        continue;
      }
      final TypeElement typeElement = switchParameter.getTypeElement();
      if (blocks.remove(getQualifiedName(typeElement)) != null) {
        fatalError("Limitation: no more than one switch per class is allowed");
        continue;
      }
      blocks.put(getQualifiedName(typeElement), new SwitchBlock(typeElement, switchParameter));
    }
    final Set<? extends Element> caseElements = roundEnv.getElementsAnnotatedWith(Case.class);
    log(caseElements.toString());
    for (final Element e : caseElements) {
      final AnnotationMirror am = getAnnotationMirror(e, Case.class);
      final ParameterInMethod caseParameter =
          new ParameterInMethod(processingEnv.getElementUtils(), (VariableElement) e).getGoodCase();
      if (caseParameter.isGoodCase() == false) {
        continue;
      }
      final TypeElement typeElement = caseParameter.methodInType.typeElement;
      final SwitchBlock block =
          OPTION_INHERIT_CASES
              ? blocks.computeIfAbsent(
                  getQualifiedName(typeElement), key -> new SwitchBlock(typeElement))
              : blocks.get(getQualifiedName(typeElement));
      if (block != null) {
        block.addCaseParameter(caseParameter);
      } else {
        error("No @Switch for the @Case", caseParameter.methodInType.methodElement, am);
      }
    }
    if (OPTION_INHERIT_CASES) {
      final var visitedKeys = new HashSet<String>();
      for (final Map.Entry<String, SwitchBlock> e : blocks.entrySet()) {
        final String key = e.getKey();
        final SwitchBlock block = e.getValue();
        if (visitedKeys.contains(key) || block.hasSwitch() == false) {
          continue;
        }
        ofNullable(getExistingSuperclass(block.typeElement))
            .ifPresent(it -> it.accept(new CaseInheritance(blocks, visitedKeys), block));
      }
    }
    blocks.values().removeIf(it -> it.hasSwitch() == false);
    blocks.forEach(
        (key, block) -> {
          writeSuperclass(block.typeElement);
          writeSubclass(block);
        });
  }

  private AnnotationMirror getAnnotationMirror(final Element e, final Class<?> annotationClass) {
    final Name fqn = processingEnv.getElementUtils().getName(annotationClass.getName());
    return e.getAnnotationMirrors()
        .stream()
        .filter(it -> asElement(it.getAnnotationType()).getQualifiedName().equals(fqn))
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    String.format("Missing annotation %s on %s", annotationClass, e)));
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
