package tilt.apt.dispatch.processor;

import javax.lang.model.type.NoType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.SimpleTypeVisitor9;

abstract class TypeName extends SimpleTypeVisitor9<Appendable, Appendable> {
  @Override
  public Appendable visitPrimitive(PrimitiveType t, Appendable p) {
    switch (t.getKind()) {
      case BOOLEAN:
      case BYTE:
      case SHORT:
      case INT:
      case LONG:
      case FLOAT:
      case DOUBLE:
      case CHAR:
        p.append(t.getKind().name().toLowerCase());
        break;
      default:
        defaultAction(t, p);
        break;
    }
    return p;
  }

  @Override
  public Appendable visitNoType(NoType t, Appendable p) {
    switch (t.getKind()) {
      case VOID:
        p.append(t.getKind().name().toLowerCase());
        break;
      default:
        defaultAction(t, p);
        break;
    }
    return p;
  }

  @Override
  protected Appendable defaultAction(TypeMirror e, Appendable p) {
    throw new IllegalArgumentException("Illegal type name: " + e);
  }
}
