package io.flottila.mapping.structure;

import java.util.Optional;

/**
 * A WSDL operation with its direction, and the fault's name when the direction is {@link
 * WsdlDirection#FAULT} (ADR-0008).
 *
 * @param interfaceName the portType (WSDL 1.1) or interface (WSDL 2.0), an EQName
 * @param operation the operation's NCName
 * @param fault the fault's NCName, present exactly when the direction is {@code FAULT}
 */
public record WsdlOperation(
    String interfaceName, String operation, WsdlDirection direction, Optional<String> fault) {

  public WsdlOperation {
    if (fault.isPresent() != (direction == WsdlDirection.FAULT)) {
      throw new IllegalArgumentException(
          "a WSDL selector names a fault exactly when its direction is FAULT");
    }
  }
}
