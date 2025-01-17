package com.duosecurity.model;

import java.io.Serial;
import java.io.Serializable;
import lombok.Data;

@Data
public class User implements Serializable {
  @Serial
  private static final long serialVersionUID = 8222207159539569949L;

  private String key;
  private String name;
}
