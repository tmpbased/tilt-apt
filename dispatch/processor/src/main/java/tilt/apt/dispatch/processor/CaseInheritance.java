package tilt.apt.dispatch.processor;

import static tilt.apt.dispatch.processor.SafeOperations.asElement;
import static tilt.apt.dispatch.processor.SafeOperations.getQualifiedName;
import static tilt.apt.dispatch.processor.UnsafeOperations.getExistingSuperclass;

import java.util.LinkedList;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.SimpleTypeVisitor9;

final class CaseInheritance extends SimpleTypeVisitor9<Void, SwitchBlock> {
  private final Map<String, SwitchBlock> blocks;
  private final Set<String> visitedKeys;
  private final LinkedList<SwitchBlock> visitedSuperBlocks;

  public CaseInheritance(final Map<String, SwitchBlock> blocks, final Set<String> visitedKeys) {
    this.blocks = blocks;
    this.visitedKeys = visitedKeys;
    this.visitedSuperBlocks = new LinkedList<SwitchBlock>();
  }

  @Override
  public Void visitDeclared(DeclaredType t, SwitchBlock firstBlock) {
    final TypeElement typeElement = asElement(t);
    final String key = getQualifiedName(typeElement);
    final SwitchBlock superBlock = blocks.get(key);
    if (superBlock != null) {
      superBlock.copyCaseParametersTo(firstBlock);
      visitedSuperBlocks.forEach(it -> superBlock.copyCaseParametersTo(it));
      visitedSuperBlocks.push(superBlock);
    }
    if (visitedKeys.add(key) == false) {
      return super.visitDeclared(t, firstBlock);
    }
    return Optional.ofNullable(getExistingSuperclass(typeElement))
        .map(it -> it.accept(this, firstBlock))
        .orElseGet(() -> defaultAction(t, firstBlock));
  }
}
