package io.mutastic.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/// What stands for a build's arguments in a record, so a run with others is never the same code. An
/// argument may be a short secret, and a plain hash of one can be guessed, so it's a keyed one,
/// under a random key that stays in the module's work directory and is in no record or report.
final class BuildArgsKey {
  private BuildArgsKey() {}

  private static final String FILE = "build-args.key";
  private static final int BYTES = 32;

  /// Empty for no arguments, so a record of a run without any is as it always was. A key lost with
  /// the work directory makes the next run other code, never the same.
  static String of(Path work, List<String> words) {
    if (words.isEmpty()) {
      return "";
    }
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key(work), "HmacSHA256"));
      words.forEach(
          word -> {
            mac.update(word.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
          });
      return HexFormat.of().formatHex(mac.doFinal());
    } catch (GeneralSecurityException e) {
      throw new AssertionError("every JDK has HmacSHA256", e);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static byte[] key(Path work) throws IOException {
    var file = work.resolve(FILE);
    if (Files.isRegularFile(file) && Files.size(file) == BYTES) {
      return Files.readAllBytes(file);
    }
    var key = new byte[BYTES];
    new SecureRandom().nextBytes(key);
    Files.createDirectories(work);
    Files.deleteIfExists(file);
    if (Files.getFileStore(work).supportsFileAttributeView("posix")) {
      Files.createFile(
          file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    }
    Files.write(file, key);
    return key;
  }
}
