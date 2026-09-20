package com.professionalpos.model;

import com.professionalpos.security.Permission;
import java.util.Set;

public record User(
    long id,
    String username,
    String name,
    String role,
    Set<Permission> permissions,
    boolean mustChange) {
  public User {
    permissions = Set.copyOf(permissions);
  }

  public boolean can(Permission p) {
    return permissions.contains(p);
  }

  public void require(Permission p) {
    if (!can(p)) throw new SecurityException("You do not have permission for this action.");
  }
}
