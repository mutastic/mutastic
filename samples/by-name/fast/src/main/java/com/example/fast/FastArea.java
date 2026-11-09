package com.example.fast;

import com.example.shapes.Area;

public class FastArea implements Area {
  @Override
  public int of(int side) {
    return side < 0 ? 0 : side * side;
  }
}
