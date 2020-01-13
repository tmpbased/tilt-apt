package tilt.apt.dispatch.processor;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Set;

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
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.tools.Diagnostic.Kind;

import com.google.auto.service.AutoService;

import tilt.apt.dispatch.annotations.Case;
import tilt.apt.dispatch.annotations.Switch;

/**
 * Processor Options:<ul>
 *   <li>debug - turns on debug statements</li>
 * </ul>
 */
@AutoService(Processor.class)
@SupportedOptions({ "debug" })
public class DispatchProcessor extends AbstractProcessor {
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
    if (roundEnv.processingOver()) {
      generateFiles();
    } else {
      processAnnotations(annotations, roundEnv);
    }
    return true;
  }

  private void processAnnotations(Set<? extends TypeElement> annotations,
      RoundEnvironment roundEnv) {

    Set<? extends Element> elements = roundEnv.getElementsAnnotatedWith(Switch.class);

    log(annotations.toString());
    log(elements.toString());

    for (Element e : elements) {
      final VariableElement switchArgument = (VariableElement) e;
      switchArgument.getSimpleName();
      switchArgument.asType().toString();
      final ExecutableElement switchMethod = (ExecutableElement) switchArgument.getEnclosingElement();
      assert switchMethod.getModifiers().contains(Modifier.ABSTRACT);
      final TypeElement switchClass = (TypeElement) switchMethod.getEnclosingElement();
    }
  }

  private void generateFiles() {
    Filer filer = processingEnv.getFiler();

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
