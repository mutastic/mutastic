package io.mutastic.worker;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/// The annotations on a class, read from its class file without loading it: loading a class defines
/// it, and the loader that defines a test class is what this decides.
///
/// The worker runs on the project's JDK (17+), so no ClassFile API: the class's
/// `RuntimeVisibleAnnotations` attribute, found by walking the class file as the JVM spec lays it
/// out, names each annotation's type in the constant pool.
final class ClassAnnotations {
  private static final String VISIBLE = "RuntimeVisibleAnnotations";

  private ClassAnnotations() {}

  /// The internal names of the annotations on the class itself (`org/example/Marker`), in order.
  static List<String> of(byte[] classFile) {
    try {
      var in = new DataInputStream(new ByteArrayInputStream(classFile));
      in.skipNBytes(8); // magic, minor, major
      var texts = texts(in);
      in.skipNBytes(6); // access, this, super
      in.skipNBytes(2L * in.readUnsignedShort()); // interfaces
      skipMembers(in); // fields
      skipMembers(in); // methods
      return visibleAnnotations(in, texts);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /// The annotations in the class's own attributes, the last part of its class file.
  private static List<String> visibleAnnotations(DataInputStream in, String[] texts)
      throws IOException {
    var found = new ArrayList<String>();
    for (int count = in.readUnsignedShort(); count > 0; count--) {
      var name = texts[in.readUnsignedShort()];
      long length = Integer.toUnsignedLong(in.readInt());
      if (VISIBLE.equals(name)) {
        annotations(in, texts, found);
      } else {
        in.skipNBytes(length);
      }
    }
    return found;
  }

  /// The constant pool's UTF-8 entries by index; null for every other entry.
  private static String[] texts(DataInputStream in) throws IOException {
    int count = in.readUnsignedShort();
    var texts = new String[count];
    for (int index = 1; index < count; index++) {
      int tag = in.readUnsignedByte();
      switch (tag) {
        case 1 -> texts[index] = in.readUTF();
        case 7, 8, 16, 19, 20 -> in.skipNBytes(2);
        case 15 -> in.skipNBytes(3);
        case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipNBytes(4);
        case 5, 6 -> {
          in.skipNBytes(8);
          index++; // longs and doubles take two slots
        }
        default -> throw new IllegalArgumentException("constant pool tag " + tag);
      }
    }
    return texts;
  }

  /// Skips the fields or the methods, each with its attributes.
  private static void skipMembers(DataInputStream in) throws IOException {
    for (int members = in.readUnsignedShort(); members > 0; members--) {
      in.skipNBytes(6); // access, name, descriptor
      for (int attributes = in.readUnsignedShort(); attributes > 0; attributes--) {
        in.skipNBytes(2);
        in.skipNBytes(Integer.toUnsignedLong(in.readInt()));
      }
    }
  }

  /// Adds each annotation's type, from its descriptor (`Lorg/example/Marker;`).
  private static void annotations(DataInputStream in, String[] texts, List<String> found)
      throws IOException {
    for (int count = in.readUnsignedShort(); count > 0; count--) {
      var descriptor = texts[in.readUnsignedShort()];
      found.add(descriptor.substring(1, descriptor.length() - 1));
      skipElements(in);
    }
  }

  /// Skips an annotation's elements: each a name and a value.
  private static void skipElements(DataInputStream in) throws IOException {
    for (int pairs = in.readUnsignedShort(); pairs > 0; pairs--) {
      in.skipNBytes(2);
      skipValue(in);
    }
  }

  private static void skipValue(DataInputStream in) throws IOException {
    int tag = in.readUnsignedByte();
    switch (tag) {
      case 'B', 'C', 'D', 'F', 'I', 'J', 'S', 'Z', 's', 'c' -> in.skipNBytes(2);
      case 'e' -> in.skipNBytes(4);
      case '@' -> {
        in.skipNBytes(2);
        skipElements(in);
      }
      case '[' -> {
        for (int values = in.readUnsignedShort(); values > 0; values--) {
          skipValue(in);
        }
      }
      default -> throw new IllegalArgumentException("element value tag " + tag);
    }
  }
}
