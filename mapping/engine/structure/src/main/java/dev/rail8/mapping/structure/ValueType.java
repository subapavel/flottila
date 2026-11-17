package dev.rail8.mapping.structure;

/** The value type of a leaf field: one of the seven atomic types. */
public enum ValueType {
  STRING("xs:string"),
  BOOLEAN("xs:boolean"),
  DECIMAL("xs:decimal"),
  INTEGER("xs:integer"),
  DATE("xs:date"),
  TIME("xs:time"),
  DATE_TIME("xs:dateTime");

  private final String typeName;

  ValueType(String typeName) {
    this.typeName = typeName;
  }

  /** The type's name as XPath spells it, for example {@code xs:dateTime}. */
  public String typeName() {
    return typeName;
  }
}
